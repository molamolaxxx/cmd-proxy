const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const html = fs.readFileSync(path.resolve(__dirname,
    '../../main/resources/configui/index.html'), 'utf8')
const start = html.indexOf('function updateAgentMemoryDreamButton()')
const end = html.indexOf('async function reloadAgentResource()', start)
assert.ok(start >= 0 && end > start)

function fixture() {
    const button = {style: {}, disabled: true, title: ''}
    const label = {textContent: ''}
    const notices = []
    let reloads = 0
    const context = vm.createContext({
        document: {getElementById: id => id === 'resourceDreamButton' ? button : label},
        resourceState: {kind: 'memory'},
        resourceDream: {robot: 'Agent A', available: false, running: false,
            pending: true, pendingText: '正在检查…', timer: null},
        showSnackbar: message => notices.push(message),
        reloadAgentResource: () => { reloads++ },
        setTimeout: () => 1,
        clearTimeout: () => {}
    })
    vm.runInContext(html.slice(start, end), context)
    return {context, button, label, notices, get reloads() { return reloads }}
}

test('memory dream stays disabled throughout background work and refreshes on completion', async () => {
    const view = fixture()
    let resolvePost
    let status = {ok: true, available: true, running: false}
    let posts = 0
    view.context.api = (url, options) => {
        if (options && options.method === 'POST') {
            posts++
            return new Promise(resolve => { resolvePost = resolve })
        }
        return Promise.resolve({ok: true, json: async () => status})
    }
    await view.context.checkAgentMemoryDream()
    assert.equal(view.button.disabled, false)
    const first = view.context.triggerAgentMemoryDream()
    await view.context.triggerAgentMemoryDream()
    assert.equal(posts, 1)
    assert.equal(view.button.disabled, true)
    assert.equal(view.label.textContent, '正在触发整理…')
    resolvePost({ok: true, json: async () => ({ok: true, running: true})})
    await first
    assert.equal(view.label.textContent, '记忆整理中…')
    assert.equal(view.button.disabled, true)
    await view.context.triggerAgentMemoryDream()
    assert.equal(posts, 1)
    status = {ok: true, available: true, running: false}
    await view.context.checkAgentMemoryDream()
    assert.equal(view.button.disabled, false)
    assert.equal(view.reloads, 1)
})

test('opening memory viewer during an existing dream shows the running label', async () => {
    const view = fixture()
    view.context.api = async () => ({ok: true, json: async () =>
        ({ok: true, available: true, running: true})})
    await view.context.checkAgentMemoryDream()
    assert.equal(view.label.textContent, '记忆整理中…')
    assert.equal(view.button.disabled, true)
})
