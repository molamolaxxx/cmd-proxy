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

test('known target label includes the latest inbound message preview', () => {
    const context = vm.createContext({})
    vm.runInContext(functionSource('channelKnownTargetLabel'), context)

    assert.equal(context.channelKnownTargetLabel({
        chatType: 'group',
        displayName: 'Jira 运维群',
        lastMessagePreview: '@Jira机器人 FCT-25123'
    }), '[群聊] Jira 运维群 · 最新：@Jira机器人 FCT-25123')
})

test('refreshing known targets preserves the outbound target draft', () => {
    const selects = {}
    const context = vm.createContext({
        channelDialogDraft: {
            knownChatTargets: [],
            outboundTargets: [{id: 'jira机器人', chatId: '', description: '通知 Jira'}]
        },
        document: {getElementById: id => selects[id] || null},
        channelOutboundTargetOptions: () => '<option>new</option>',
        normalizeChannelOutboundTargets: ch => ch.outboundTargets
    })
    vm.runInContext(functionSource('applyChannelKnownTargets'), context)

    assert.equal(context.applyChannelKnownTargets([{
        id: 'group-1', displayName: 'Jira 群', chatType: 'group'
    }]), true)
    assert.equal(context.channelDialogDraft.outboundTargets[0].id, 'jira机器人')
    assert.equal(context.channelDialogDraft.outboundTargets[0].description, '通知 Jira')
    assert.equal(context.channelDialogDraft.knownChatTargets[0].id, 'group-1')
})
