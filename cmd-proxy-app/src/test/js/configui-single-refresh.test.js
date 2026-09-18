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

test('applies only the saved channel and advances its runtime identity', async () => {
    const calls = []
    const states = []
    const context = vm.createContext({
        config: {channels: [{id: 'channel-new', _runtimeId: 'channel-old'}]},
        api: async (url, options) => {
            calls.push([url, JSON.parse(options.body)])
            return {ok: true, json: async () => ({ok: true})}
        },
        markItemRefreshing: (type, key) => states.push(['start', type, key]),
        loadItemRefreshStatus: async () => states.push(['finish', 'channels']),
        loadChannelStatus: async () => {},
        renderChannels() {}
    })
    vm.runInContext(section(
        'async function applyChannelConfig',
        'async function refreshChannel('
    ), context)

    await context.applyChannelConfig(0)

    assert.deepEqual(calls, [[
        '/api/refresh-channel',
        {previousChannelId: 'channel-old', channelId: 'channel-new'}
    ]])
    assert.equal(context.config.channels[0]._runtimeId, 'channel-new')
    assert.deepEqual(states, [
        ['start', 'channels', 'channel-new'],
        ['finish', 'channels']
    ])
})

test('applies one robot then reloads ordinary and team session projections', async () => {
    const calls = []
    const projections = []
    const states = []
    const context = vm.createContext({
        config: {robots: [{name: 'Robot New', _runtimeName: 'Robot Old'}]},
        api: async (url, options) => {
            calls.push([url, JSON.parse(options.body)])
            return {ok: true, json: async () => ({ok: true})}
        },
        markItemRefreshing: (type, key) => states.push(['start', type, key]),
        loadItemRefreshStatus: async () => states.push(['finish', 'robots']),
        renderRobots() {},
        loadStarweaveSessions: async () => projections.push('sessions'),
        loadStarweaveTeams: async () => projections.push('teams'),
        refreshChannelBindingTargets: async () => projections.push('channels')
    })
    vm.runInContext(section(
        'async function applyRobotConfig',
        'async function refreshRobot('
    ), context)

    await context.applyRobotConfig(0)

    assert.deepEqual(calls, [[
        '/api/refresh-robot',
        {previousName: 'Robot Old', name: 'Robot New'}
    ]])
    assert.equal(context.config.robots[0]._runtimeName, 'Robot New')
    assert.deepEqual(projections, ['sessions', 'teams', 'channels'])
    assert.deepEqual(states, [
        ['start', 'robots', 'Robot New'],
        ['finish', 'robots']
    ])
})
