'use strict';

const assert = require('assert');
const {
    MAX_PROMPT_BYTES,
    MAX_ARGV_PROMPT_BYTES,
    MAX_SINGLE_MESSAGE_BYTES,
    byteLen,
    clipToBytes,
    compactContent,
    buildPromptWithHistory,
    fitPromptContext,
    shouldUsePromptFile,
} = require('./prompt-context');

assert.ok(clipToBytes('hello', 100) === 'hello');
assert.ok(clipToBytes('abcdefghijklmnopqrstuvwxyz', 10).startsWith('…'));
assert.ok(byteLen(clipToBytes('a'.repeat(5000), 100)) <= 100);

const stripped = compactContent('intro\n<thinking>\nhuge secret dump\n</thinking>\n\n\nactual reply');
assert.ok(!stripped.includes('huge secret dump'));
assert.ok(stripped.includes('actual reply'));

const huge = 'x'.repeat(MAX_SINGLE_MESSAGE_BYTES + 4000);
assert.ok(byteLen(compactContent(huge)) <= MAX_SINGLE_MESSAGE_BYTES);

const hist = [];
for (let i = 0; i < 12; i++) {
    hist.push({ role: 'user', content: `user turn ${i} ` + 'u'.repeat(8000) });
    hist.push({
        role: 'assistant',
        content: `<thinking>${'t'.repeat(20000)}</thinking>\nreply ${i} ` + 'a'.repeat(20000),
    });
}

const fitted = fitPromptContext('please continue', hist, ['note-one'], 40000);
assert.ok(fitted.bytes <= 40000, `fitted.bytes=${fitted.bytes}`);
assert.ok(fitted.historyKept < hist.length, 'should drop oldest turns');
assert.ok(fitted.text.includes('[User]: please continue'));
assert.ok(!fitted.text.includes('<thinking>'));

// Repro: 10 fat messages → ~133KiB after naive last-10 keep (old E2BIG).
const ten = [];
for (let i = 0; i < 5; i++) {
    ten.push({ role: 'user', content: `q${i} ` + 'q'.repeat(4000) });
    ten.push({
        role: 'assistant',
        content: `<thinking>${'th'.repeat(15000)}</thinking>\n` + 'body '.repeat(4000),
    });
}
const naive = buildPromptWithHistory('go on', ten, []);
assert.ok(byteLen(naive) > 131072, `expected naive assembly over MAX_ARG_STRLEN, got ${byteLen(naive)}`);
const safe = fitPromptContext('go on', ten, []);
assert.ok(safe.bytes <= MAX_PROMPT_BYTES, `safe.bytes=${safe.bytes}`);
assert.ok(safe.text.includes('[User]: go on'));

assert.strictEqual(shouldUsePromptFile('tiny', 0), false);
assert.strictEqual(shouldUsePromptFile('tiny', 1), true);
assert.strictEqual(shouldUsePromptFile('y'.repeat(MAX_ARGV_PROMPT_BYTES + 10), 0), true);

const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');
const e2big = spawnSync('/bin/true', ['-p', 'x'.repeat(140000)]);
assert.ok(e2big.error && e2big.error.code === 'E2BIG', '140KiB argv should E2BIG');
const tmp = path.join(os.tmpdir(), 'gos-prompt-e2big-test.txt');
fs.writeFileSync(tmp, 'x'.repeat(140000));
const viaFile = spawnSync('/bin/true', ['--prompt-file', tmp]);
assert.ok(!viaFile.error, 'prompt file must not E2BIG');
fs.unlinkSync(tmp);

console.log('prompt-context tests ok');
