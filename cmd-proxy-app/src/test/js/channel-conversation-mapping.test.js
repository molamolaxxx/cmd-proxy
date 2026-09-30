const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/configui/assets/js/channels.js'), 'utf8')
function fixture() {
    const nodes = {}, messages = []
    const context = vm.createContext({
        document: {getElementById: id => nodes[id] || null},
        esc: value => String(value).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;'),
        showSnackbar: message => messages.push(message),
        channelDialogDraft: {
            binding: {type:'TEAM_MEMBER', teamId:'team-1', teamMemberSelection:'CONVERSATION_MAPPING', conversationMappings:[]},
            outboundTargets: [{id:'ops', chatId:'chat-1', description:'通知'}],
            knownChatTargets: [{id:'chat-1', chatType:'group', displayName:'收入群'}, {id:'user-1', chatType:'single', displayName:'张三'}]
        }
    })
    vm.runInContext(source, context)
    context.renderChannelDialogBody = () => {}
    return {context, nodes, messages}
}
test('many conversations may map to one member, but duplicate conversations are rejected', () => {
    const {context:c, messages} = fixture()
    c.addChannelConversationMapping(); c.addChannelConversationMapping()
    c.setChannelMappingConversation(0, JSON.stringify(['group','chat-1']))
    c.setChannelMappingConversation(1, JSON.stringify(['group','chat-1']))
    assert.equal(messages.length, 1)
    assert.equal(c.channelDialogDraft.binding.conversationMappings[1].conversationId, '')
    c.setChannelMappingConversation(1, JSON.stringify(['single','user-1']))
    c.channelDialogDraft.binding.conversationMappings.forEach(m => m.teamMemberId='member-a')
    assert.equal(c.validateChannelConversationMappings(c.channelDialogDraft, {members:[{id:'member-a'}]}), '')
    assert.match(c.validateChannelConversationMappings(c.channelDialogDraft, {members:[]}), /有效团队成员/)
})
test('search and runtime discovery refresh preserve selections, members and outbound draft', () => {
    const {context:c, nodes} = fixture()
    c.addChannelConversationMapping()
    c.setChannelMappingConversation(0, JSON.stringify(['group','chat-1']))
    c.channelDialogDraft.binding.conversationMappings[0].teamMemberId='member-a'
    nodes.channelMappingChat_0 = {value:'新群',dataset:{query:'新群'},getAttribute:()=> 'true'}
    nodes.channelMappingMenu_0 = {innerHTML:''}
    c.applyChannelKnownTargets([{id:'chat-2', chatType:'group', displayName:'新群'}])
    assert.match(nodes.channelMappingMenu_0.innerHTML, /chat-2/)
    assert.match(c.channelMappingOptions(0,''), /历史会话/)
    assert.equal(nodes.channelMappingChat_0.value, '新群')
    assert.equal(c.channelDialogDraft.binding.conversationMappings[0].conversationId, 'chat-1')
    assert.equal(c.channelDialogDraft.binding.conversationMappings[0].teamMemberId, 'member-a')
    assert.equal(c.channelDialogDraft.outboundTargets[0].description, '通知')
})
test('type is part of identity and a row can be removed without changing the other mapping', () => {
    const {context:c} = fixture()
    c.addChannelConversationMapping(); c.addChannelConversationMapping()
    c.setChannelMappingConversation(0, JSON.stringify(['group','same']))
    c.setChannelMappingConversation(1, JSON.stringify(['single','same']))
    c.channelDialogDraft.binding.conversationMappings.forEach(m => m.teamMemberId='member-a')
    assert.equal(c.validateChannelConversationMappings(c.channelDialogDraft, {members:[{id:'member-a'}]}), '')
    c.removeChannelConversationMapping(0)
    assert.equal(c.channelDialogDraft.binding.conversationMappings[0].chatType, 'single')
    c.removeChannelConversationMapping(0)
    assert.equal(c.validateChannelConversationMappings(c.channelDialogDraft, {members:[]}), '')
})
test('rendering exposes routing mode only for ordinary teams and retains the mapping list', () => {
    const {context:c, nodes} = fixture()
    delete c.renderChannelDialogBody
    vm.runInContext(source, c)
    nodes.channelDialogBody={innerHTML:''}
    c.availableGroups=()=>[]
    c.channelBindingTargets={teams:[{id:'team-1', mode:'NORMAL', members:[{id:'member-a',name:'收入专家'}]}]}
    c.channelDialogDraft.binding.conversationMappings=[{chatType:'group',conversationId:'chat-1',teamMemberId:'member-a'}]
    c.renderChannelDialogBody()
    assert.match(nodes.channelDialogBody.innerHTML, /value="CONVERSATION_MAPPING"/)
    assert.match(nodes.channelDialogBody.innerHTML, /会话路由映射/)
    assert.match(nodes.channelDialogBody.innerHTML, /收入专家/)
    assert.match(nodes.channelDialogBody.innerHTML, /role="combobox"/)
    assert.doesNotMatch(nodes.channelDialogBody.innerHTML, /channelMappingSearch_/)
    assert.doesNotMatch(nodes.channelDialogBody.innerHTML, /<select id="channelMappingChat_/)
    c.channelBindingTargets.teams[0].mode='CAPTAIN'
    c.channelBindingTargets.teams[0].captainTeamMemberId='member-a'
    c.renderChannelDialogBody()
    assert.doesNotMatch(nodes.channelDialogBody.innerHTML, /value="CONVERSATION_MAPPING"/)
    assert.equal(c.channelDialogDraft.binding.teamMemberSelection, 'FIXED')
})

test('combined picker searches, selects and restores committed selection when closed', () => {
    const {context:c,nodes} = fixture()
    c.addChannelConversationMapping()
    const classes=new Set(),attributes={}
    const input={value:'',dataset:{},closest:()=>({classList:{add:v=>classes.add(v),remove:v=>classes.delete(v)}}),
        setAttribute:(k,v)=>attributes[k]=v,getAttribute:k=>attributes[k],select:()=>{}}
    nodes.channelMappingChat_0=input
    nodes.channelMappingMenu_0={innerHTML:''}
    c.openChannelMappingPicker(0)
    assert.equal(attributes['aria-expanded'],'true')
    c.filterChannelMappingOptions(0,'张三')
    assert.match(nodes.channelMappingMenu_0.innerHTML,/张三/)
    assert.doesNotMatch(nodes.channelMappingMenu_0.innerHTML,/收入群/)
    c.chooseChannelMappingOption(0,JSON.stringify(['single','user-1']))
    assert.equal(attributes['aria-expanded'],'false')
    assert.match(input.value,/张三/)
    c.openChannelMappingPicker(0)
    input.value='does not exist'
    c.filterChannelMappingOptions(0,input.value)
    assert.match(nodes.channelMappingMenu_0.innerHTML,/没有匹配/)
    c.closeChannelMappingPicker(0)
    assert.match(input.value,/张三/)
    assert.equal(c.channelDialogDraft.binding.conversationMappings[0].conversationId,'user-1')
})

test('conversation options separate name, preview and ID and mark the actual selection', () => {
    const {context:c}=fixture()
    c.addChannelConversationMapping()
    c.channelDialogDraft.knownChatTargets[0].lastMessagePreview='长消息 <script>danger</script>'
    c.setChannelMappingConversation(0,JSON.stringify(['group','chat-1']))
    const html=c.channelMappingOptions(0,'')
    assert.match(html,/channel-conversation-heading/)
    assert.match(html,/channel-conversation-preview">最新消息：长消息 &lt;script>/)
    assert.match(html,/channel-conversation-id">群 ID：chat-1/)
    assert.equal((html.match(/aria-label="已选中"/g)||[]).length,1)
    assert.equal(c.channelMappingLabel(c.channelDialogDraft.binding.conversationMappings[0]),'[群聊] 收入群')
    assert.match(c.channelMappingTooltip(c.channelDialogDraft.binding.conversationMappings[0]),/最新消息：长消息/)
    assert.doesNotMatch(html,/<script>/)
})
test('unnamed groups are distinguishable by ID suffix without expanding the closed field', () => {
    const {context:c}=fixture()
    assert.equal(c.channelConversationTitle({chatType:'group',displayName:'未提供群名',id:'wr57CoCQAA7xq6K6ZeTVE1VoS45Hxohq'}),'[群聊] 未提供群名 · S45Hxohq')
})
