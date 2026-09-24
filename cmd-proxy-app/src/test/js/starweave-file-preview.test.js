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

const context = vm.createContext({
    document: {
        createElement() {
            let value = ''
            return {
                set textContent(next) { value = String(next) },
                get innerHTML() {
                    return value.replace(/&/g, '&amp;').replace(/</g, '&lt;')
                        .replace(/>/g, '&gt;')
                }
            }
        }
    }
})
vm.runInContext(section('function parseStarFilePreviewTarget', 'function fileLinkContext'), context)
vm.runInContext(section('function inlineMarkdown', 'function splitMarkdownTableRow'), context)
vm.runInContext(section('function esc(s)', '// 服务设置区域'), context)
vm.runInContext(section('function messageAttachmentsHtml', 'function renderStarweaveEvents'), context)
vm.runInContext(section('function teamHistoryItemHtml', 'function teamLiveItemHtml'), context)
vm.runInContext(section('function teamLiveItemHtml', 'function renderTeamSessionMessages'), context)

test('parses local file links and line numbers without confusing remote ports', () => {
    assert.deepEqual(JSON.parse(JSON.stringify(
        context.parseStarFilePreviewTarget('/workspace/App.java:42')
    )), { target: '/workspace/App.java', requestedLine: 42, remote: false })
    assert.deepEqual(JSON.parse(JSON.stringify(
        context.parseStarFilePreviewTarget('file:///workspace/README.md#L8')
    )), { target: 'file:///workspace/README.md', requestedLine: 8, remote: false })
    assert.deepEqual(JSON.parse(JSON.stringify(
        context.parseStarFilePreviewTarget('https://example.com:8443/app.js#L3')
    )), { target: 'https://example.com:8443/app.js', requestedLine: 3, remote: true })
    assert.equal(context.parseStarFilePreviewTarget('javascript:alert(1)'), null)
})

test('renders local markdown targets as preview links and keeps web links external', () => {
    const local = context.inlineMarkdown('[App](/workspace/App.java:42)')
    assert.match(local, /class="star-file-link"/)
    assert.match(local, /data-file-target="\/workspace\/App.java:42"/)

    const remote = context.inlineMarkdown('[Docs](https://example.com/docs.html)')
    assert.doesNotMatch(remote, /star-file-link/)
    assert.match(remote, /target="_blank"/)
})

test('binds the shared viewer to ordinary and team session message roots', () => {
    assert.match(html, /target\.closest\('#starSessionMessages'\)/)
    assert.match(html, /target\.closest\('#teamSessionMessages'\)/)
    assert.match(html, /\/api\/starweave\/v1\/sessions\/file-preview/)
    assert.match(html, /\/api\/starweave\/v1\/teams\/file-preview/)
    assert.match(html, /id="fileLinkDownloadButton"/)
    assert.match(html, /function downloadFileLinkPreview\(\)/)
    assert.match(html, /#fileLinkPreviewDialog\{z-index:360\}/)
})

test('renders attachment names in live and restored team user bubbles', () => {
    context.teamPersistedEventHtml = () => ''
    context.teamTalkToCardHtml = () => ''
    context.renderMarkdown = value => value
    context.normalized = value => value
    context.taskEventCardHtml = () => ''
    context.eventCardHtml = () => ''

    const restored = context.teamHistoryItemHtml({
        role: 'USER',
        content: 'please inspect',
        attachments: [{fileName: 'screen.png'}, {fileName: '<notes>.txt'}]
    })
    const live = context.teamLiveItemHtml({
        kind: 'user',
        text: 'please inspect',
        attachments: [{fileName: 'screen.png'}]
    })

    assert.match(restored, /message-attachments/)
    assert.match(restored, /screen\.png/)
    assert.match(restored, /&lt;notes&gt;\.txt/)
    assert.match(live, /screen\.png/)
})

test('keeps ready upload metadata on the optimistic team user bubble', async () => {
    const input = {value: 'please inspect'}
    const member = {state: 'READY', sessionId: 'session-1'}
    const sent = []
    const sendContext = vm.createContext({
        teamSession: {
            uploads: [{uploadId: 'upload-1', fileName: 'screen.png', size: 128}],
            liveItems: []
        },
        selectedTeamSessionMember: () => member,
        document: {getElementById: id => id === 'teamSessionInput' ? input : null},
        teamSessionPost: async (action, body) => sent.push({action, body}),
        showSnackbar() {},
        renderTeamSessionMembers() {},
        renderTeamSessionDetail() {},
        connectTeamSessionStream() {}
    })
    vm.runInContext(section('async function sendTeamSessionMessage',
        'function teamSessionKeydown'), sendContext)

    await sendContext.sendTeamSessionMessage()

    assert.deepEqual(JSON.parse(JSON.stringify(sent)), [{
        action: 'send',
        body: {message: 'please inspect', sessionId: 'session-1', uploadIds: ['upload-1']}
    }])
    assert.deepEqual(JSON.parse(JSON.stringify(sendContext.teamSession.liveItems)), [{
        kind: 'user',
        text: 'please inspect',
        attachments: [{fileName: 'screen.png', size: 128}]
    }])
    assert.equal(sendContext.teamSession.uploads.length, 0)
})

test('runs a new session action for every ready team member and blocks stale rosters', async () => {
    const calls = []
    const messages = []
    const batch = vm.createContext({
        starTeams: {items: []},
        teamBatchOperations: Object.create(null),
        renderStarweaveTeams() {},
        loadStarweaveTeams: async () => {},
        refreshChannelBindingTargets: async () => {},
        showConfirm: async () => true,
        showSnackbar(message) { messages.push(message) },
        postTeamMemberAction: async (team, member, action) => {
            calls.push([team.teamId, member.teamMemberId, action])
        }
    })
    vm.runInContext(section('async function runTeamBatchAction', 'function starTeamSourceKey'), batch)
    batch.starTeams.items = [{teamId: 'ready-team', members: [
        {teamMemberId: 'a', state: 'READY'},
        {teamMemberId: 'b', state: 'READY'}
    ]}]

    await batch.runTeamBatchAction('ready-team', 'newSession')

    assert.deepEqual(calls, [
        ['ready-team', 'a', 'newSession'],
        ['ready-team', 'b', 'newSession']
    ])
    assert.equal(messages.at(-1), '所有团队成员的新会话已创建')
    assert.equal(batch.teamBatchOperations['ready-team'], undefined)

    calls.length = 0
    batch.starTeams.items = [{teamId: 'starting-team', members: [
        {teamMemberId: 'a', state: 'READY'},
        {teamMemberId: 'b', state: 'STARTING'}
    ]}]
    await batch.runTeamBatchAction('starting-team', 'newSession')
    assert.equal(calls.length, 0)
    assert.equal(messages.at(-1), '无法新建会话：请等待所有团队成员进入 READY')
})
