#!/usr/bin/env node

const fs = require("fs");
const path = require("path");

const [sourceHome, targetRoot] = process.argv.slice(2);
if (!sourceHome || !targetRoot) {
    console.error("Usage: sanitize-claude-config.js SOURCE_HOME TARGET_ROOT");
    process.exit(2);
}

const sourceJson = path.join(sourceHome, ".claude.json");
const sourceDir = path.join(sourceHome, ".claude");
const targetJson = path.join(targetRoot, ".claude.json");
const targetDir = path.join(targetRoot, ".claude");
const replacement = "[REDACTED]";
const apiKeyName = /api.?key|access.?key/i;

if (!fs.statSync(sourceJson).isFile() || !fs.statSync(sourceDir).isDirectory()) {
    throw new Error(`Claude configuration is incomplete under ${sourceHome}`);
}

fs.mkdirSync(targetRoot, { recursive: true });
fs.copyFileSync(sourceJson, targetJson);
fs.cpSync(sourceDir, targetDir, {
    recursive: true,
    dereference: false,
    preserveTimestamps: true,
});

const structuredFiles = [
    targetJson,
    ...fs.readdirSync(targetDir)
        .filter((name) => /^settings.*\.json$/i.test(name))
        .map((name) => path.join(targetDir, name)),
];
const secretValues = new Set();
let redactedFields = 0;

function redactApiKeyFields(value) {
    if (Array.isArray(value)) {
        value.forEach(redactApiKeyFields);
        return;
    }
    if (!value || typeof value !== "object") return;

    for (const [key, child] of Object.entries(value)) {
        if (apiKeyName.test(key) && typeof child === "string" && child !== replacement) {
            if (child.length >= 16 && !child.startsWith("$") && !/^\[?redacted\]?$/i.test(child)) {
                secretValues.add(child);
            }
            value[key] = replacement;
            redactedFields++;
        } else {
            redactApiKeyFields(child);
        }
    }
}

for (const file of structuredFiles) {
    const stat = fs.statSync(file);
    const document = JSON.parse(fs.readFileSync(file, "utf8"));
    redactApiKeyFields(document);
    fs.writeFileSync(file, `${JSON.stringify(document, null, 2)}\n`, { mode: stat.mode });
    fs.chmodSync(file, stat.mode);
}

if (redactedFields === 0) {
    throw new Error("No API/access-key fields were found; refusing to produce an unverified snapshot");
}

let regularFiles = 0;
function visit(directory) {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
        const file = path.join(directory, entry.name);
        if (entry.isDirectory()) {
            visit(file);
            continue;
        }
        if (!entry.isFile()) continue;
        regularFiles++;
        if (secretValues.size === 0) continue;

        const original = fs.readFileSync(file);
        if (original.includes(0)) continue;
        let text = original.toString("utf8");
        let changed = false;
        for (const secret of secretValues) {
            if (text.includes(secret)) {
                text = text.split(secret).join(replacement);
                changed = true;
            }
        }
        if (changed) {
            const mode = fs.statSync(file).mode;
            fs.writeFileSync(file, text, { mode });
            fs.chmodSync(file, mode);
        }
    }
}

visit(targetRoot);

for (const secret of secretValues) {
    function assertAbsent(directory) {
        for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
            const file = path.join(directory, entry.name);
            if (entry.isDirectory()) {
                assertAbsent(file);
            } else if (entry.isFile()) {
                const content = fs.readFileSync(file);
                if (!content.includes(0) && content.toString("utf8").includes(secret)) {
                    throw new Error(`API key remains after sanitization: ${file}`);
                }
            }
        }
    }
    assertAbsent(targetRoot);
}

console.log(`Claude snapshot staged: files=${regularFiles}, redactedFields=${redactedFields}, distinctSecrets=${secretValues.size}`);
