const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const html = fs.readFileSync(path.resolve(
    __dirname, '../../main/resources/configui/index.html'
), 'utf8')

function section(start, end) {
    const from = html.indexOf(start)
    const to = html.indexOf(end, from)
    assert.notEqual(from, -1, `missing ${start}`)
    assert.notEqual(to, -1, `missing ${end}`)
    return html.slice(from, to)
}

function sidebar() {
    let open = false
    return {
        get open() { return open },
        classList: {
            toggle(name) {
                assert.equal(name, 'open')
                open = !open
            }
        }
    }
}

test('mobile session backdrops close the matching sidebar', () => {
    const ordinary = sidebar()
    const team = sidebar()
    const context = vm.createContext({
        document: {
            querySelector(selector) {
                if (selector === '.session-sidebar') return ordinary
                if (selector === '#teamSessionDialog .session-sidebar') return team
                return null
            }
        }
    })
    vm.runInContext(section('function toggleStarSessionSidebar()', 'function contextUsageHtml'), context)
    vm.runInContext(section('function toggleTeamSessionSidebar()', 'async function loadTeamSessionSnapshot'), context)

    const cases = [
        {list: 'starSessionList', handler: 'toggleStarSessionSidebar', target: ordinary},
        {list: 'teamSessionMemberList', handler: 'toggleTeamSessionSidebar', target: team}
    ]
    for (const {list, handler, target} of cases) {
        const markup = html.match(new RegExp(
            `id="${list}"[^]*?</aside><button[^>]+class="session-sidebar-backdrop"[^>]+onclick="([^"]+)"[^>]*></button><div class="session-main">`
        ))
        assert.ok(markup, `missing backdrop for ${list}`)
        assert.equal(markup[1], `${handler}()`)

        context[handler]()
        assert.equal(target.open, true)
        vm.runInContext(markup[1], context)
        assert.equal(target.open, false)
    }

    assert.match(html, /@media\(max-width:860px\)\{\.session-sidebar\.open \+ \.session-sidebar-backdrop\{display:block;position:absolute;inset:0;z-index:19;/)
    assert.match(html, /\.session-sidebar\.open\{display:flex;position:absolute;[^}]*z-index:20;/)
})
