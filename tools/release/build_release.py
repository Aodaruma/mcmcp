"""Build the Japanese guide and an allowlisted distribution ZIP without a GUI.

Run with Python 3.11+, requirements.txt and `python -m playwright install chromium`.
"""
from __future__ import annotations

import argparse
import hashlib
import html
from pathlib import Path
import re
import shutil
import tempfile
import urllib.request
import unicodedata
import zipfile

ROOT = Path(__file__).resolve().parents[2]
FONT_URL = ('https://raw.githubusercontent.com/google/fonts/'
            '5e35378e6bda803962ee6fd257e444a7d459660d/ofl/notosansjp/NotoSansJP%5Bwght%5D.ttf')
FONT_SHA256 = 'c2f3b4d463500a2ddcd3849cded1fceeb9fd6d1c32e6cbecd568453ba50fc68f'
VERSION_RE = re.compile(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*))?')


def version_from_tag(tag: str) -> str:
    if not tag.startswith('v') or not VERSION_RE.fullmatch(tag[1:]):
        raise ValueError('Tag must be vMAJOR.MINOR.PATCH or vMAJOR.MINOR.PATCH-prerelease (e.g. v0.1.0-rc3)')
    prerelease = tag[1:].split('-', 1)[1:] or ['']
    if any(len(p) > 1 and p.isdigit() and p.startswith('0') for p in prerelease[0].split('.')):
        raise ValueError('Numeric prerelease identifiers must not have leading zeros')
    return tag[1:]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def properties() -> dict[str, str]:
    return dict(line.split('=', 1) for line in (ROOT / 'gradle.properties').read_text().splitlines()
                if '=' in line and not line.startswith('#'))


def font_file() -> Path:
    path = ROOT / 'build/release-fonts/NotoSansJP.ttf'
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(FONT_URL, timeout=60) as response:
            data = response.read(16 * 1024 * 1024)
        if hashlib.sha256(data).hexdigest() != FONT_SHA256:
            raise ValueError('Downloaded font SHA256 mismatch')
        path.write_bytes(data)
    if sha256(path) != FONT_SHA256:
        raise ValueError('Cached font SHA256 mismatch')
    return path


def guide_markdown(version: str) -> str:
    text = (ROOT / 'docs/MCMCP_配布用README.md').read_text(encoding='utf-8')
    text, count = re.subn(r'^- MODの配布版：.*$', f'- MODの配布版：**{version}**', text, flags=re.M)
    if count != 1:
        raise ValueError('Guide version marker is missing or duplicated')
    text = re.sub(r'mcmcp-neoforge-26\.2-[A-Za-z0-9.+-]+\.jar',
                  f'mcmcp-neoforge-26.2-{version}.jar', text)
    return text


def render_pdf(markdown: str, destination: Path, asset_dir: Path, font: Path) -> None:
    from markdown_it import MarkdownIt
    from playwright.sync_api import sync_playwright

    # GitHub callouts are readable in both ordinary Markdown and this print stylesheet.
    markdown = re.sub(r'^> \[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]\s*$',
                      lambda match: '> **' + match[1].title() + '**', markdown, flags=re.M)
    # Typora accepts Japanese punctuation-adjacent emphasis more broadly than CommonMark.
    # Preserve code fences while making that existing guide convention explicit HTML.
    fenced = False
    lines = []
    for line in markdown.splitlines():
        if line.startswith(('```', '~~~')):
            fenced = not fenced
        if not fenced:
            line = re.sub(r'\*\*([^*\n]+)\*\*', r'<strong>\1</strong>', line)
        lines.append(line)
    markdown = '\n'.join(lines)
    content = MarkdownIt('commonmark', {'html': True}).enable('table').render(markdown)
    content = content.replace('<div style="page-break-after: always;"></div>',
                              '</section><section class="guide-page">')
    css = (ROOT / 'tools/release/guide.css').read_text().replace('__FONT_URL__', font.as_uri())
    document = ('<!doctype html><html lang="ja"><meta charset="utf-8">'
                '<title>MCMCP 導入ガイド</title>'
                f'<base href="{html.escape(asset_dir.as_uri())}/"><style>{css}</style>'
                f'<body><main><section class="guide-page">{content}</section></main></body></html>')
    destination.parent.mkdir(parents=True, exist_ok=True)
    html_path = destination.with_suffix('.html')
    html_path.write_text(document, encoding='utf-8')
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        page = browser.new_page()
        # Screenshots, font and CSS are local. Rendering never contacts embedded external links.
        page.route('http://**/*', lambda route: route.abort())
        page.route('https://**/*', lambda route: route.abort())
        page.goto(html_path.as_uri(), wait_until='load')
        page.evaluate('document.fonts.ready')
        if not page.evaluate('document.fonts.check(\'12px "MCMCP Guide"\')'):
            raise ValueError('Japanese font did not load')
        missing = page.locator('img').evaluate_all('(images) => images.filter(i => !i.complete || !i.naturalWidth).map(i => i.getAttribute("src"))')
        if missing:
            raise ValueError(f'Guide images did not load: {missing}')
        page.pdf(path=str(destination), prefer_css_page_size=True, print_background=True,
                 display_header_footer=True, header_template='<span></span>',
                 footer_template='<div style="font-size:9px;width:100%;text-align:center;color:#64748b">'
                                 '<span class="pageNumber"></span> / <span class="totalPages"></span></div>',
                 tagged=True, outline=True)
        browser.close()


def update_toc(markdown: str, pdf: Path) -> str:
    from pypdf import PdfReader
    pages = [[re.sub(r'\s+', '', unicodedata.normalize('NFKC', line))
              for line in page.extract_text().splitlines()]
             for page in PdfReader(pdf).pages]
    entries = re.findall(r'^\| ([^|]+) \| (\d+) \|$', markdown, flags=re.M)
    for title, old_page in entries:
        if not re.match(r'\d+\.', title):
            continue
        heading = re.sub(r'\s+', '', unicodedata.normalize('NFKC', title.split(' / ')[0]))
        matches = [index + 1 for index, lines in enumerate(pages)
                   if index > 0 and any(line.startswith(heading) for line in lines)]
        if not matches:
            raise ValueError(f'TOC heading not found in PDF: {title}')
        markdown = markdown.replace(f'| {title} | {old_page} |', f'| {title} | {matches[0]} |')
    return markdown


def verify_pdf(pdf: Path, version: str) -> None:
    from pypdf import PdfReader
    pages = [unicodedata.normalize('NFKC', page.extract_text()) for page in PdfReader(pdf).pages]
    if any(len(re.sub(r'\s+', '', text)) < 40 for text in pages):
        raise ValueError('Unexpected blank or nearly empty PDF page')
    full_text = '\n'.join(pages)
    if any(marker in full_text for marker in ('**', '\ufffd')):
        raise ValueError('Unrendered markup or replacement glyph in PDF')
    for required in (version, 'F3+P', 'MCP接続設定', 'トラブルシューティング'):
        if required not in full_text:
            raise ValueError(f'Required guide content missing: {required}')


def verify_jar(jar: Path, version: str) -> None:
    import tomllib
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        metadata = tomllib.loads(archive.read('META-INF/neoforge.mods.toml').decode())
        if metadata['mods'][0]['modId'] != 'mcmcp' or metadata['mods'][0]['version'] != version:
            raise ValueError('Production JAR version does not match requested release')
        if metadata['license'] != 'MPL-2.0':
            raise ValueError('Production JAR must declare MPL-2.0')
        if any(name.startswith(('dev/aod/mcmcp/fixture/', 'dev/aod/mcmcp/adminbridge/')) for name in names):
            raise ValueError('Development-only classes leaked into production JAR')


def build(version: str, jar: Path | None, output: Path, tag: str | None = None) -> Path:
    if not VERSION_RE.fullmatch(version):
        raise ValueError('Invalid version')
    if jar is not None:
        verify_jar(jar, version)
    output.mkdir(parents=True, exist_ok=True)
    markdown = guide_markdown(version)
    font = font_file()
    with tempfile.TemporaryDirectory(prefix='mcmcp-guide-', dir=ROOT / 'build') as temporary:
        staging = Path(temporary)
        shutil.copytree(ROOT / 'docs/assets/readme', staging / 'assets/readme',
                        ignore=lambda directory, names: [name for name in names if not name.endswith('.png')])
        pdf = staging / 'README.pdf'
        render_pdf(markdown, pdf, staging, font)
        updated = update_toc(markdown, pdf)
        if updated != markdown:
            markdown = updated
            render_pdf(markdown, pdf, staging, font)
            if update_toc(markdown, pdf) != markdown:
                raise ValueError('TOC pagination did not stabilize')
        verify_pdf(pdf, version)
        (staging / 'README.md').write_text(markdown, encoding='utf-8')
        shutil.copy2(pdf, output / 'README.pdf')
        shutil.copy2(staging / 'README.md', output / 'README.md')
        if jar is None:
            return output / 'README.pdf'
        shutil.copy2(jar, staging / jar.name)
        for name in ('LICENSE', 'NOTICE.md'):
            shutil.copy2(ROOT / name, staging / name)
        shutil.copy2(ROOT / 'tools/release/OFL-NotoSansJP.txt', staging / 'OFL-NotoSansJP.txt')
        shutil.copy2(ROOT / 'docs/MCMCP_Action_DSL_クイックガイド.md', staging / 'ACTION_DSL.md')
        source = 'https://github.com/Aodaruma/mcmcp/tree/' + (tag or 'main')
        (staging / 'SOURCE.txt').write_text(f'MCMCP {version}\nMPL-2.0 source: {source}\n', encoding='utf-8')
        allowed = [path for path in staging.rglob('*') if path.is_file() and path.suffix != '.html']
        checksums = ''.join(f'{sha256(path)}  {path.relative_to(staging).as_posix()}\n'
                            for path in sorted(allowed))
        (staging / 'SHA256SUMS.txt').write_text(checksums, encoding='utf-8')
        allowed.append(staging / 'SHA256SUMS.txt')
        package = output / f'mcmcp-neoforge-26.2-{version}.zip'
        with zipfile.ZipFile(package, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for path in sorted(allowed):
                archive.write(path, path.relative_to(staging).as_posix())
        with zipfile.ZipFile(package) as archive:
            if archive.testzip() is not None or archive.read(jar.name) != jar.read_bytes():
                raise ValueError('Distribution ZIP verification failed')
        (output / f'{package.name}.sha256').write_text(f'{sha256(package)}  {package.name}\n', encoding='utf-8')
        return package


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag')
    parser.add_argument('--version')
    parser.add_argument('--print-version', action='store_true')
    parser.add_argument('--docs-only', action='store_true')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/release')
    args = parser.parse_args()
    version = version_from_tag(args.tag) if args.tag else args.version or properties()['mod_version']
    if args.print_version:
        print(version)
        return
    jar = None if args.docs_only else ROOT / f'build/libs/mcmcp-neoforge-26.2-{version}.jar'
    print(build(version, jar, args.output.resolve(), args.tag))


if __name__ == '__main__':
    main()
