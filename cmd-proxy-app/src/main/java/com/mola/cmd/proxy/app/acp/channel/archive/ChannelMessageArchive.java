package com.mola.cmd.proxy.app.acp.channel.archive;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelAttachment;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelQuotedMessage;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Instance-owned durable archive for external inbound messages. */
public final class ChannelMessageArchive implements AutoCloseable {
    private static final class Holder {
        private static final ChannelMessageArchive INSTANCE =
                new ChannelMessageArchive(CmdProxyHome.resolve("channels", "messages"));
    }

    public static ChannelMessageArchive getInstance() { return Holder.INSTANCE; }

    public static final class Receipt {
        private final String id;
        private final boolean first;
        Receipt(String id, boolean first) { this.id = id; this.first = first; }
        public String getId() { return id; }
        public boolean isFirst() { return first; }
    }

    public static final class AttachmentResource {
        private final String fileName;
        private final String mimeType;
        private final long size;
        private final Path path;
        AttachmentResource(String fileName, String mimeType, long size, Path path) {
            this.fileName = fileName; this.mimeType = mimeType; this.size = size; this.path = path;
        }
        public String getFileName() { return fileName; }
        public String getMimeType() { return mimeType; }
        public long getSize() { return size; }
        public InputStream open() throws IOException { return Files.newInputStream(path); }
    }

    private final Path root;
    private final Path attachmentRoot;
    private final Connection connection;
    private final ChannelPayloadSanitizer sanitizer = new ChannelPayloadSanitizer();

    public ChannelMessageArchive(Path root) {
        try {
            this.root = root.toAbsolutePath().normalize();
            this.attachmentRoot = this.root.resolve("attachments").normalize();
            Files.createDirectories(attachmentRoot);
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + this.root.resolve("messages.db"));
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
            }
            migrate();
        } catch (Exception e) {
            throw new IllegalStateException("channel message archive unavailable", e);
        }
    }

    private void migrate() throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS channel_message_schema(version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS channel_message("
                    + "id TEXT PRIMARY KEY, archive_id TEXT NOT NULL, channel_id TEXT NOT NULL, provider_message_id TEXT, "
                    + "request_id_digest TEXT, first_received_at INTEGER NOT NULL, last_received_at INTEGER NOT NULL, duplicate_count INTEGER NOT NULL DEFAULT 0, "
                    + "chat_type TEXT, chat_id TEXT, chat_name TEXT, sender_id TEXT, sender_name TEXT, sender_alias TEXT, "
                    + "message_type TEXT, text_content TEXT, quote_type TEXT, quote_text TEXT, sanitized_payload_json TEXT NOT NULL, "
                    + "parse_status TEXT NOT NULL, delivery_status TEXT NOT NULL, result_code TEXT, result_detail TEXT, "
                    + "attachment_count INTEGER NOT NULL DEFAULT 0, attachment_bytes INTEGER NOT NULL DEFAULT 0, processed_at INTEGER, updated_at INTEGER NOT NULL)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS channel_message_dedup ON channel_message(archive_id,provider_message_id) WHERE provider_message_id IS NOT NULL AND provider_message_id<>''");
            s.execute("CREATE INDEX IF NOT EXISTS channel_message_time ON channel_message(archive_id,first_received_at DESC,id DESC)");
            s.execute("CREATE INDEX IF NOT EXISTS channel_message_sender ON channel_message(archive_id,sender_id,first_received_at DESC)");
            s.execute("CREATE INDEX IF NOT EXISTS channel_message_chat ON channel_message(archive_id,chat_type,chat_id,first_received_at DESC)");
            s.execute("CREATE TABLE IF NOT EXISTS channel_message_attachment("
                    + "id TEXT PRIMARY KEY, message_id TEXT NOT NULL REFERENCES channel_message(id) ON DELETE CASCADE, origin TEXT NOT NULL, ordinal INTEGER NOT NULL, "
                    + "kind TEXT NOT NULL, file_name TEXT NOT NULL, mime_type TEXT NOT NULL, size_bytes INTEGER NOT NULL, sha256 TEXT NOT NULL, storage_key TEXT NOT NULL, created_at INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS channel_attachment_message ON channel_message_attachment(message_id,ordinal)");
            s.execute("INSERT OR IGNORE INTO channel_message_schema VALUES(1,strftime('%s','now')*1000)");
        }
    }

    public synchronized Receipt receive(String archiveId, String channelId, String requestId,
                                        JsonObject body) {
        String messageId = string(body, "msgid");
        long now = System.currentTimeMillis();
        if (!blank(messageId)) {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE channel_message SET last_received_at=?,duplicate_count=duplicate_count+1,updated_at=? WHERE archive_id=? AND provider_message_id=?")) {
                bind(update, now, now, archiveId, messageId);
                if (update.executeUpdate() == 1) return new Receipt(findId(archiveId, messageId), false);
            } catch (SQLException e) { throw storage(e); }
        }
        JsonObject from = object(body, "from");
        String id = UUID.randomUUID().toString();
        String chatType = string(body, "chattype");
        String chatId = string(body, "chatid");
        String chatName = first(string(body, "chatname"), string(body, "chat_name"));
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO channel_message(id,archive_id,channel_id,provider_message_id,request_id_digest,first_received_at,last_received_at,chat_type,chat_id,chat_name,sender_id,sender_name,sender_alias,message_type,sanitized_payload_json,parse_status,delivery_status,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            bind(insert, id, required(archiveId), required(channelId), emptyToNull(messageId), digest(requestId), now, now,
                    chatType, chatId, chatName, string(from, "userid"), string(from, "name"), string(from, "alias"),
                    string(body, "msgtype"), sanitizer.sanitize(body), "RECEIVED", "PENDING", now);
            insert.executeUpdate();
            return new Receipt(id, true);
        } catch (SQLException e) {
            if (!blank(messageId) && isConstraint(e)) {
                try {
                    String existing = findId(archiveId, messageId);
                    return new Receipt(existing, false);
                } catch (SQLException nested) { e.addSuppressed(nested); }
            }
            throw storage(e);
        }
    }

    public synchronized void completeParsed(String id, String text, ChannelQuotedMessage quote,
                                            List<ChannelAttachment> attachments) throws IOException {
        List<ArchivedFile> archived = new ArrayList<>();
        int total = 0;
        int ordinal = 0;
        for (ChannelAttachment attachment : attachments == null
                ? java.util.Collections.<ChannelAttachment>emptyList() : attachments) {
            byte[] content = attachment.getContent();
            String sha = hex(content);
            String storageKey = sha.substring(0, 2) + "/" + sha;
            Path target = resolveStorage(storageKey);
            if (!Files.exists(target)) {
                Files.createDirectories(target.getParent());
                Path temp = Files.createTempFile(root, "channel-attachment-", ".tmp");
                try {
                    Files.write(temp, content);
                    try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE); }
                    catch (java.nio.file.FileAlreadyExistsException ignored) { }
                    catch (AtomicMoveNotSupportedException e) {
                        try { Files.move(temp, target); }
                        catch (java.nio.file.FileAlreadyExistsException ignored) { }
                    }
                } finally { Files.deleteIfExists(temp); }
            }
            String attachmentId = UUID.randomUUID().toString();
            archived.add(new ArchivedFile(attachmentId, attachment, ordinal++, sha, storageKey));
            total += content.length;
        }
        try {
            connection.setAutoCommit(false);
            execute("DELETE FROM channel_message_attachment WHERE message_id=?", id);
            for (ArchivedFile file : archived) {
                execute("INSERT INTO channel_message_attachment(id,message_id,origin,ordinal,kind,file_name,mime_type,size_bytes,sha256,storage_key,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        file.id, id, file.source.getOrigin().name(), file.ordinal,
                        file.source.getKind().name(), safeFileName(file.source.getFileName()),
                        blank(file.source.getMimeType()) ? "application/octet-stream" : file.source.getMimeType(),
                        file.source.size(), file.sha, file.storageKey, System.currentTimeMillis());
            }
            execute("UPDATE channel_message SET text_content=?,quote_type=?,quote_text=?,parse_status='PARSED',attachment_count=?,attachment_bytes=?,updated_at=? WHERE id=?",
                    text == null ? "" : text, quote == null ? null : quote.getMessageType(),
                    quote == null ? null : quote.getText(), archived.size(), total,
                    System.currentTimeMillis(), id);
            connection.commit();
        } catch (Exception e) {
            try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
            throw new IOException("message metadata persistence failed", e);
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) { }
        }
    }

    public synchronized void mark(String id, String parseStatus, String deliveryStatus,
                                  String code, String detail) {
        long now = System.currentTimeMillis();
        try {
            execute("UPDATE channel_message SET parse_status=COALESCE(?,parse_status),delivery_status=COALESCE(?,delivery_status),result_code=?,result_detail=?,processed_at=?,updated_at=? WHERE id=?",
                    parseStatus, deliveryStatus, code, truncate(detail, 1000), now, now, id);
        } catch (SQLException e) { throw storage(e); }
    }

    public synchronized JSONObject list(String archiveId, java.util.Map<String, String> filters,
                                        int page, int pageSize) {
        StringBuilder where = new StringBuilder(" WHERE m.archive_id=?");
        List<Object> args = new ArrayList<>(); args.add(required(archiveId));
        exact(where, args, filters, "messageId", "m.provider_message_id");
        exact(where, args, filters, "senderId", "m.sender_id");
        like(where, args, filters, "senderName", "m.sender_name");
        exact(where, args, filters, "chatType", "m.chat_type");
        exact(where, args, filters, "chatId", "m.chat_id");
        like(where, args, filters, "chatName", "m.chat_name");
        exact(where, args, filters, "messageType", "m.message_type");
        exact(where, args, filters, "deliveryStatus", "m.delivery_status");
        like(where, args, filters, "content", "m.text_content");
        like(where, args, filters, "quote", "m.quote_text");
        String mimeType = clean(filters.get("mimeType"));
        if (!mimeType.isEmpty()) { where.append(" AND EXISTS(SELECT 1 FROM channel_message_attachment ma WHERE ma.message_id=m.id AND ma.mime_type LIKE ? ESCAPE '\\')"); args.add(likeValue(mimeType)); }
        range(where, args, filters, "receivedFrom", ">=");
        range(where, args, filters, "receivedTo", "<=");
        String attachmentName = clean(filters.get("attachmentName"));
        if (!attachmentName.isEmpty()) { where.append(" AND EXISTS(SELECT 1 FROM channel_message_attachment a WHERE a.message_id=m.id AND a.file_name LIKE ? ESCAPE '\\')"); args.add(likeValue(attachmentName)); }
        String keyword = clean(filters.get("keyword"));
        if (!keyword.isEmpty()) {
            where.append(" AND (m.provider_message_id LIKE ? ESCAPE '\\' OR m.sender_id LIKE ? ESCAPE '\\' OR m.sender_name LIKE ? ESCAPE '\\' OR m.chat_id LIKE ? ESCAPE '\\' OR m.chat_name LIKE ? ESCAPE '\\' OR m.text_content LIKE ? ESCAPE '\\' OR m.quote_text LIKE ? ESCAPE '\\' OR EXISTS(SELECT 1 FROM channel_message_attachment ka WHERE ka.message_id=m.id AND ka.file_name LIKE ? ESCAPE '\\'))");
            String value = likeValue(keyword); for (int i = 0; i < 8; i++) args.add(value);
        }
        long total;
        try { total = scalar("SELECT COUNT(*) FROM channel_message m" + where, args); }
        catch (SQLException e) { throw storage(e); }
        JSONArray items = new JSONArray();
        List<Object> queryArgs = new ArrayList<>(args); queryArgs.add(pageSize); queryArgs.add((page - 1) * pageSize);
        String sql = "SELECT m.* FROM channel_message m" + where + " ORDER BY m.first_received_at DESC,m.id DESC LIMIT ? OFFSET ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, queryArgs.toArray());
            try (ResultSet r = statement.executeQuery()) { while (r.next()) {
                JSONObject item = row(r, false);
                item.put("attachments", attachments(item.getString("id")));
                items.add(item);
            } }
        } catch (SQLException e) { throw storage(e); }
        JSONObject data = new JSONObject(true); data.put("page", page); data.put("pageSize", pageSize);
        data.put("total", total); data.put("totalPages", Math.max(1L, (total + pageSize - 1) / pageSize)); data.put("items", items);
        return data;
    }

    public synchronized JSONObject detail(String archiveId, String id) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM channel_message WHERE archive_id=? AND id=?")) {
            bind(statement, required(archiveId), required(id));
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) return null;
                JSONObject item = row(r, true); item.put("attachments", attachments(id)); return item;
            }
        } catch (SQLException e) { throw storage(e); }
    }

    public synchronized AttachmentResource attachment(String archiveId, String messageId,
                                                      String attachmentId) {
        String sql = "SELECT a.file_name,a.mime_type,a.size_bytes,a.storage_key FROM channel_message_attachment a JOIN channel_message m ON m.id=a.message_id WHERE m.archive_id=? AND m.id=? AND a.id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, required(archiveId), required(messageId), required(attachmentId));
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) return null;
                Path path = resolveStorage(r.getString(4));
                if (!Files.isRegularFile(path)) return null;
                return new AttachmentResource(r.getString(1), r.getString(2), r.getLong(3), path);
            }
        } catch (SQLException | IOException e) { throw storage(e); }
    }

    private JSONObject row(ResultSet r, boolean includePayload) throws SQLException {
        JSONObject item = new JSONObject(true);
        item.put("id", r.getString("id")); item.put("channelId", r.getString("channel_id"));
        item.put("messageId", r.getString("provider_message_id")); item.put("receivedAt", r.getLong("first_received_at"));
        item.put("lastReceivedAt", r.getLong("last_received_at")); item.put("duplicateCount", r.getInt("duplicate_count"));
        JSONObject sender = new JSONObject(true); sender.put("id", r.getString("sender_id")); sender.put("name", r.getString("sender_name")); sender.put("alias", r.getString("sender_alias")); item.put("sender", sender);
        JSONObject chat = new JSONObject(true); chat.put("type", r.getString("chat_type")); chat.put("id", r.getString("chat_id")); chat.put("name", r.getString("chat_name")); item.put("chat", chat);
        item.put("messageType", r.getString("message_type")); item.put("textContent", r.getString("text_content"));
        JSONObject quote = new JSONObject(true); quote.put("messageType", r.getString("quote_type")); quote.put("text", r.getString("quote_text")); item.put("quote", quote);
        item.put("parseStatus", r.getString("parse_status")); item.put("deliveryStatus", r.getString("delivery_status")); item.put("resultCode", r.getString("result_code")); item.put("resultDetail", r.getString("result_detail"));
        item.put("attachmentCount", r.getInt("attachment_count")); item.put("attachmentBytes", r.getLong("attachment_bytes")); item.put("processedAt", nullableLong(r, "processed_at"));
        if (includePayload) item.put("sanitizedPayload", com.alibaba.fastjson.JSON.parse(r.getString("sanitized_payload_json")));
        return item;
    }

    private JSONArray attachments(String messageId) throws SQLException {
        JSONArray items = new JSONArray();
        try (PreparedStatement s = connection.prepareStatement("SELECT id,origin,ordinal,kind,file_name,mime_type,size_bytes,sha256 FROM channel_message_attachment WHERE message_id=? ORDER BY ordinal")) {
            bind(s, messageId); try (ResultSet r = s.executeQuery()) { while (r.next()) {
                JSONObject a = new JSONObject(true); a.put("id", r.getString(1)); a.put("origin", r.getString(2)); a.put("ordinal", r.getInt(3)); a.put("kind", r.getString(4)); a.put("fileName", r.getString(5)); a.put("mimeType", r.getString(6)); a.put("size", r.getLong(7)); a.put("sha256", r.getString(8)); items.add(a);
            }}
        }
        return items;
    }

    private long scalar(String sql, List<Object> args) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement(sql)) { bind(s, args.toArray()); try (ResultSet r = s.executeQuery()) { return r.next() ? r.getLong(1) : 0; } }
    }
    private void execute(String sql, Object... args) throws SQLException { try (PreparedStatement s = connection.prepareStatement(sql)) { bind(s, args); s.executeUpdate(); } }
    private static void bind(PreparedStatement s, Object... args) throws SQLException { for (int i=0;i<args.length;i++) s.setObject(i+1,args[i]); }
    private String findId(String archiveId, String messageId) throws SQLException { try (PreparedStatement s=connection.prepareStatement("SELECT id FROM channel_message WHERE archive_id=? AND provider_message_id=?")){bind(s,archiveId,messageId);try(ResultSet r=s.executeQuery()){if(r.next())return r.getString(1);throw new SQLException("dedup row missing");}} }
    private Path resolveStorage(String key) throws IOException { Path path=attachmentRoot.resolve(key).normalize();if(!path.startsWith(attachmentRoot))throw new IOException("invalid attachment storage key");return path; }
    private static void exact(StringBuilder w,List<Object>a,java.util.Map<String,String>f,String key,String col){String v=clean(f.get(key));if(!v.isEmpty()){w.append(" AND ").append(col).append("=?");a.add(v);}}
    private static void like(StringBuilder w,List<Object>a,java.util.Map<String,String>f,String key,String col){String v=clean(f.get(key));if(!v.isEmpty()){w.append(" AND ").append(col).append(" LIKE ? ESCAPE '\\'");a.add(likeValue(v));}}
    private static void range(StringBuilder w,List<Object>a,java.util.Map<String,String>f,String key,String operation){String v=clean(f.get(key));if(v.isEmpty())return;try{long time=Long.parseLong(v);w.append(" AND m.first_received_at ").append(operation).append(" ?");a.add(time);}catch(NumberFormatException e){throw new IllegalArgumentException(key+" must be epoch milliseconds");}}
    private static String likeValue(String v){return "%"+v.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";}
    private static String clean(String v){if(v==null)return "";v=v.trim();return v.length()>200?v.substring(0,200):v;}
    private static String string(JsonObject o,String n){if(o==null||!o.has(n)||o.get(n).isJsonNull())return null;try{return o.get(n).getAsString();}catch(Exception e){return null;}}
    private static JsonObject object(JsonObject o,String n){return o!=null&&o.has(n)&&o.get(n).isJsonObject()?o.getAsJsonObject(n):new JsonObject();}
    private static String first(String a,String b){return blank(a)?b:a;}
    private static String required(String v){if(blank(v))throw new IllegalArgumentException("required value missing");return v.trim();}
    private static String emptyToNull(String v){return blank(v)?null:v;}
    private static boolean blank(String v){return v==null||v.trim().isEmpty();}
    private static String truncate(String v,int max){return v==null?null:(v.length()<=max?v:v.substring(0,max));}
    private static boolean isConstraint(SQLException e){return e.getErrorCode()==19||String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("constraint");}
    private static RuntimeException storage(Exception e){return new IllegalStateException("channel message storage unavailable",e);}
    private static Long nullableLong(ResultSet r,String name)throws SQLException{long v=r.getLong(name);return r.wasNull()?null:v;}
    private static String safeFileName(String name){String value=blank(name)?"attachment.bin":name.replace('\\','_').replace('/','_').replace('\0','_');return value.length()>200?value.substring(0,200):value;}
    private static String digest(String value){if(blank(value))return null;try{return toHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String hex(byte[] content){try{return toHex(MessageDigest.getInstance("SHA-256").digest(content));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String toHex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    private static final class ArchivedFile { final String id;final ChannelAttachment source;final int ordinal;final String sha;final String storageKey;ArchivedFile(String id,ChannelAttachment source,int ordinal,String sha,String storageKey){this.id=id;this.source=source;this.ordinal=ordinal;this.sha=sha;this.storageKey=storageKey;} }
    @Override public synchronized void close(){try{connection.close();}catch(SQLException e){throw storage(e);}}
}
