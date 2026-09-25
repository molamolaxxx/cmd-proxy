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

test('outbound target id accepts Chinese and keeps route delimiters forbidden', () => {
    const context = vm.createContext({})
    vm.runInContext(functionSource('isValidChannelOutboundTargetId'), context)

    assert.equal(context.isValidChannelOutboundTargetId('jira机器人'), true)
    assert.equal(context.isValidChannelOutboundTargetId('研发群-1'), true)
    assert.equal(context.isValidChannelOutboundTargetId('产品_值班群'), true)
    assert.equal(context.isValidChannelOutboundTargetId('研发 群'), false)
    assert.equal(context.isValidChannelOutboundTargetId('channel:研发群'), false)
})
