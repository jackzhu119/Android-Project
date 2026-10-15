from pathlib import Path
from datetime import datetime
from zoneinfo import ZoneInfo
import hashlib
import html
import json
import shutil
from playwright.sync_api import sync_playwright

ROOT = Path('/workspace/Android-Project')
OUT = ROOT / 'coursework/week02/evidence'
SRC = OUT / 'source'
SRC.mkdir(parents=True, exist_ok=True)
STAMP = datetime.now(ZoneInfo('Asia/Shanghai')).strftime('%Y-%m-%d %H:%M:%S UTC+08:00')

def read(relative):
    return (ROOT / relative).read_text(encoding='utf-8')

def source_lines(text, numbers, highlights=()):
    lines = text.splitlines()
    parts = []
    previous = None
    for number in numbers:
        if previous is not None and number != previous + 1:
            parts.append('<div class="line omission"><span>…</span><code>部分内容省略</code></div>')
        cls = 'line highlight' if number in highlights else 'line'
        parts.append(f'<div class="{cls}"><span>{number}</span><code>{html.escape(lines[number-1])}</code></div>')
        previous = number
    return '<div class="code">' + ''.join(parts) + '</div>'

def card(title, path, body, extra=''):
    return f'<section class="card {extra}"><div class="cardhead"><strong>{title}</strong><small>{path}</small></div>{body}</section>'

STYLE = '''
* { box-sizing: border-box; }
html,body { margin:0; width:1280px; height:900px; overflow:hidden; }
body { background:#eff4f8; color:#17334c; font:20px "Noto Sans CJK SC",sans-serif; }
.page { padding:40px 44px 26px; height:900px; display:flex; flex-direction:column; }
.eyebrow { color:#267386; font-size:15px; font-weight:700; letter-spacing:2px; }
h1 { font-size:34px; margin:9px 0 9px; font-weight:750; letter-spacing:.2px; }
.intro { font-size:18px; color:#526a7b; margin:0 0 24px; }
.grid { display:grid; grid-template-columns:1.12fr 1fr; gap:22px; flex:1; min-height:0; }
.stack { display:flex; flex-direction:column; gap:20px; }
.card { border:1px solid #d6e0e8; border-radius:14px; overflow:hidden; background:white; box-shadow:0 6px 16px #18365008; }
.cardhead { background:#f9fbfd; padding:17px 20px 15px; border-bottom:1px solid #e3e9ef; }
.cardhead strong { display:block; font-size:19px; }
.cardhead small { display:block; color:#657d8d; font:14px "Noto Sans Mono CJK SC",monospace; margin-top:6px; overflow-wrap:anywhere; }
.code { padding:17px 0; }
.line { display:flex; padding:3px 15px 3px 0; line-height:1.5; font-size:17px; }
.line>span { width:45px; flex:none; text-align:right; padding-right:14px; color:#9aabb7; font:15px "Noto Sans Mono CJK SC",monospace; }
.line code { font-family:"Noto Sans Mono CJK SC",monospace; white-space:pre-wrap; overflow-wrap:anywhere; }
.line.highlight { background:#e4f5f2; }
.omission { color:#8394a1; font-size:12px; }
.note { margin:15px 20px 18px; font-size:16px; line-height:1.65; color:#597181; }
table { border-collapse:collapse; width:calc(100% - 40px); margin:14px 20px 10px; font-size:17px; }
td { padding:10px 0; border-bottom:1px solid #e9eef2; }
td:first-child { width:44%; color:#207587; font-family:"Noto Sans Mono CJK SC",monospace; }
.footer { margin-top:18px; display:flex; justify-content:space-between; gap:15px; font-size:13px; color:#718697; }
.pill { display:inline-block; padding:7px 13px; border-radius:8px; font-size:16px; background:#ddf4eb; color:#12664d; font-weight:650; }
.terminal { background:#132b3b; color:#d9e8ee; border-radius:12px; padding:22px 26px; }
.terminal pre { margin:0; white-space:pre-wrap; overflow-wrap:anywhere; font:18px/1.75 "Noto Sans Mono CJK SC",monospace; }
.command { color:#83e0cf; }
.success { color:#98ebbd; font-weight:700; }
.log-label { color:#617f92; font-size:15px; margin:15px 0 10px; }
.build-grid { display:grid; grid-template-columns:1.65fr 1fr; gap:22px; flex:1; }
.info { padding:22px; }
.info h2 { font-size:21px; margin:0 0 14px; }
.info p { font-size:17px; line-height:1.8; margin:0 0 20px; }
.info code { font:14px/1.7 "Noto Sans Mono CJK SC",monospace; overflow-wrap:anywhere; }
.warn { border-color:#ecd9cc; }
.warn .cardhead { background:#fff5ee; }
.errorcode { margin:20px; background:#fff4f0; padding:17px 18px; border-left:4px solid #d77655; border-radius:6px; }
.errorcode pre { font:16px/1.7 "Noto Sans Mono CJK SC",monospace; margin:0; white-space:pre-wrap; overflow-wrap:anywhere; }
.comparison { display:grid; grid-template-columns:1fr 1.06fr; gap:22px; flex:1; min-height:0; }
.bottomnote { background:#e6f2f5; padding:15px 19px; border-radius:10px; font-size:16px; line-height:1.65; margin-top:18px; }
'''

def page(title, intro, body, footnote):
    return f'''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>{title}</title><style>{STYLE}</style></head><body><main class="page"><div class="eyebrow">移动医疗开发课程设计 · 第二周佐证</div><h1>{title}</h1><p class="intro">{intro}</p>{body}<div class="footer"><span>{footnote}</span><span>整理时间：{STAMP}</span></div></main></body></html>'''

build = read('app/build.gradle')
settings = read('settings.gradle')
v27 = read('app/src/main/res/values-v27/styles.xml')
base = read('app/src/main/res/values/styles.xml')
historical = Path('/tmp/finger-lint.log').read_text(encoding='utf-8')
shutil.copyfile('/tmp/finger-lint.log', SRC / 'historical-lint.log')
for rel in ['app/build.gradle', 'settings.gradle', 'app/src/main/res/values-v27/styles.xml', 'app/src/main/res/values/styles.xml']:
    dest = SRC / rel
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(read(rel), encoding='utf-8')

package_root = ROOT / 'app/src/main/java/com/fingerrehab'
packages = sorted(p.name for p in package_root.iterdir() if p.is_dir())
(SRC / 'package-directories.json').write_text(json.dumps(packages, ensure_ascii=False, indent=2), encoding='utf-8')
roles = {'ui':'界面与交互', 'training':'训练交互与会话', 'algorithm':'训练算法', 'database':'本地数据访问', 'utils':'公共辅助方法'}
assert all(name in packages for name in roles)
rows = ''.join(f'<tr><td>{name}/</td><td>{description}</td></tr>' for name, description in roles.items())
settings_lines = [i+1 for i, s in enumerate(settings.splitlines()) if s.startswith('rootProject.name') or s.startswith('include')]
config_lines = list(range(5,13)) + list(range(17,21))
body1 = '<div class="grid">' + card('Android 模块编译配置（节选）', 'app/build.gradle', source_lines(build, config_lines, (7,11,12,18,19,20)) + '<p class="note">核对 SDK 版本、最低兼容版本与 Java 编译选项，确认基础构建环境。</p>') + '<div class="stack">' + card('工程入口配置（节选）', 'settings.gradle', source_lines(settings, settings_lines)) + card('包目录与职责（节选）', 'app/src/main/java/com/fingerrehab/', '<table>'+rows+'</table><p class="note">目录名称来自当前工作区；本页仅核对基础分层，不代表各业务模块本周完成验收。</p>') + '</div></div>'
(OUT / '01_工程配置与目录.html').write_text(page('工程配置与基础目录核对', '工作区源文件预览 · 直接读取真实配置与现有目录生成，非 Android Studio 截图。', body1, '来源：项目配置与目录；行号对应当前源文件。'), encoding='utf-8')

log = (SRC / 'assembleDebug.log').read_text(encoding='utf-8')
selected_names = ['preBuild', 'processDebugResources', 'compileDebugJavaWithJavac', 'validateSigningDebug', 'packageDebug', 'assembleDebug']
build_lines = [line for line in log.splitlines() if any(line.startswith('> Task :app:'+name+' ') for name in selected_names)]
result_lines = [line for line in log.splitlines() if line.startswith('BUILD SUCCESSFUL') or 'actionable tasks:' in line]
assert len(result_lines) == 2
selected = '\n'.join(build_lines) + '\n\n' + '\n'.join(result_lines)
(SRC / 'assembleDebug-excerpt.txt').write_text(selected+'\n', encoding='utf-8')
log_markup = '\n'.join('<span class="success">'+html.escape(line)+'</span>' if line.startswith('BUILD SUCCESSFUL') else html.escape(line) for line in selected.splitlines())
body2 = '<div class="build-grid"><div><div class="terminal"><pre><span class="command">$ ./gradlew :app:assembleDebug --no-daemon</span>\n\n'+log_markup+'</pre></div><p class="log-label">原始日志摘录：保留关键任务及最终结果；中间任务和通用提示省略。</p></div><section class="card info"><h2>本次基础验证</h2><p><span class="pill">退出码 0 · 构建成功</span></p><p>验证范围：Debug 工程编译与 APK 打包。</p><p>命令执行目录<br><code>/workspace/Android-Project</code></p><p>APK 输出位置<br><code>app/build/outputs/apk/debug/app-debug.apk</code></p><p>本次为增量构建，<code>UP-TO-DATE</code> 表示任务结果已是最新；不表示完成各项业务功能测试。</p></section></div>'
(OUT / '02_Debug基础构建.html').write_text(page('Debug 基础构建复核', '真实命令输出 · 本次重新执行构建；截图为原始日志的本地浏览器预览。', body2, '原始完整日志：source/assembleDebug.log；本次未执行全量单元测试。'), encoding='utf-8')

old_lines = historical.splitlines()
err_index = next(i for i, line in enumerate(old_lines) if 'Error: android:windowLightNavigationBar requires API level 27' in line)
historical_excerpt = '\n'.join(old_lines[err_index:err_index+3]) + '\n\n' + next(line for line in old_lines if line.startswith('> Task :app:lintDebug FAILED'))
(SRC / 'historical-lint-excerpt.txt').write_text(historical_excerpt+'\n', encoding='utf-8')
left = card('历史兼容性错误（已修复）', '/tmp/finger-lint.log · 原始日志摘录', '<div class="errorcode"><pre>'+html.escape(historical_excerpt)+'</pre></div><p class="note">问题原因：最低支持 API 26，但浅色导航栏属性需要 API 27。历史日志记录的是修复前的检查失败。</p><p class="note">取自工程早期兼容性检查记录；后续已迁移至 API 27 专属资源目录。</p>', 'warn')
right = card('当前修复后的资源文件', 'app/src/main/res/values-v27/styles.xml', source_lines(v27, list(range(1,8)), (5,)) + '<p class="note">API 27 专属属性放到 values-v27，交由 Android 根据系统版本选择资源。</p>' + '<div class="cardhead"><strong>基础主题（节选）</strong><small>app/src/main/res/values/styles.xml</small></div>' + source_lines(base, [8], (8,)))
body3 = '<div class="comparison">'+left+right+'</div><div class="bottomnote">当前文件核对：基础 values/styles.xml 不含该 API 27 属性；values-v27/styles.xml 单独定义。历史报错与当前修复状态分开标注。</div>'
(OUT / '03_API兼容性调试记录.html').write_text(page('API 兼容性问题与修复核对', '历史调试记录 · 当前已修复 · 原始报错与真实源文件对照预览，非 IDE 截图。', body3, f'历史日志摘录行号：{err_index+1}—{err_index+3}；完整副本：source/historical-lint.log。'), encoding='utf-8')

source_map = {
    'generated_at': STAMP,
    'rendering': 'Chromium via Playwright; rendered local HTML pages using actual source and log excerpts; not Android Studio screenshots',
    'command': {'cwd': str(ROOT), 'environment_activation': 'source /workspace/.android-environment/activate.sh', 'command': './gradlew :app:assembleDebug --no-daemon', 'exit_code': 0, 'result': result_lines},
    'screenshots': [
        {'file': '01_工程配置与目录.png', 'html': '01_工程配置与目录.html', 'sources': [{'file':'app/build.gradle','lines':config_lines}, {'file':'settings.gradle','lines':settings_lines}, {'directory':'app/src/main/java/com/fingerrehab/', 'selected_packages': list(roles)}]},
        {'file': '02_Debug基础构建.png', 'html': '02_Debug基础构建.html', 'sources': [{'file':'source/assembleDebug.log', 'excerpts':selected}]},
        {'file': '03_API兼容性调试记录.png', 'html': '03_API兼容性调试记录.html', 'sources': [{'original_file':'/tmp/finger-lint.log', 'preserved_copy':'source/historical-lint.log','excerpt_lines':[err_index+1,err_index+2,err_index+3]}, {'file':'app/src/main/res/values-v27/styles.xml','lines':list(range(1,8))}, {'file':'app/src/main/res/values/styles.xml','lines':[8]}], 'note':'Historical real error; current source contains fix. No errors were introduced for evidence.'}
    ]
}
with sync_playwright() as p:
    browser = p.chromium.launch(executable_path='/usr/bin/chromium', headless=True, args=['--no-sandbox'])
    context = browser.new_context(viewport={'width':1280, 'height':900}, device_scale_factor=1)
    page_obj = context.new_page()
    for item in source_map['screenshots']:
        page_obj.set_content((OUT / item['html']).read_text(encoding='utf-8'), wait_until='load')
        page_obj.evaluate('document.fonts.ready')
        page_obj.screenshot(path=str(OUT / item['file']), full_page=False)
        item['sha256'] = hashlib.sha256((OUT / item['file']).read_bytes()).hexdigest()
    browser.close()
(OUT / 'source_map.json').write_text(json.dumps(source_map, ensure_ascii=False, indent=2), encoding='utf-8')
print('Rendered 3 genuine-source evidence screenshots at', OUT)
