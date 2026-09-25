const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const html = fs.readFileSync(path.resolve(
    __dirname, '../../main/resources/configui/index.html'
), 'utf8')

function functionSource(name) {
    const start = html.indexOf(`function ${name}(`)
    assert.notEqual(start, -1, `missing function ${name}`)
    const next = html.indexOf('\nfunction ', start + 1)
    assert.notEqual(next, -1, `missing function boundary after ${name}`)
    return html.slice(start, next)
}

test('SLEEP is operable anywhere READY is accepted', () => {
    const context = vm.createContext({})
    vm.runInContext(functionSource('isAgentOperable'), context)

    assert.equal(context.isAgentOperable('READY'), true)
    assert.equal(context.isAgentOperable('SLEEP'), true)
    assert.equal(context.isAgentOperable('BUSY'), false)
    assert.equal(context.isAgentOperable('ERROR'), false)
})

test('auto sleep editor defaults off and persists only idle threshold', () => {
    assert.match(html, /autoSleep:\{enabled:false,idleMinutes:30\}/)
    assert.match(html, /id="dAutoSleep"/)
    assert.match(html, /id="dAutoSleepIdle" min="1"/)
    assert.match(html,
        /autoSleep:\{enabled:document\.getElementById\("dAutoSleep"\)\.checked,idleMinutes:Math\.max\(1,parseInt\(document\.getElementById\("dAutoSleepIdle"\)\.value\)\|\|30\)\}/)
    assert.doesNotMatch(html, /dAutoSleepCheck/)
})

test('auto sleep editor toggles threshold visibility', () => {
    const panel = {style: {display: 'none'}}
    const toggle = {checked: true}
    const context = vm.createContext({
        document: {getElementById: id => id === 'dAutoSleep' ? toggle : panel}
    })
    vm.runInContext(functionSource('toggleAutoSleepPanel'), context)

    context.toggleAutoSleepPanel()
    assert.equal(panel.style.display, '')
    toggle.checked = false
    context.toggleAutoSleepPanel()
    assert.equal(panel.style.display, 'none')
})

test('sleep state and wake lifecycle have visible projections', () => {
    assert.match(html, /\.session-status\.sleep\{/)
    assert.match(html, /session-status '\+normalized\(member\.state\)/)
    assert.match(html, /LIFECYCLE_EVENT/)
    assert.match(html, /智能体已唤醒/)
})
