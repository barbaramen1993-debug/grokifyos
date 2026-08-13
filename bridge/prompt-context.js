'use strict';

/**
 * Assemble + size-limit the prompt the bridge hands to `grok`.
 *
 * Linux rejects a single argv entry larger than MAX_ARG_STRLEN (32 pages,
 * typically 131072 bytes) with E2BIG — even when ARG_MAX is 2MiB. Long chats
 * used to pass the whole history as `grok -p <prompt>` and crash the worker.
 */

const MAX_PROMPT_BYTES = 120000;
/** Stay well under Linux MAX_ARG_STRLEN so `-p` stays safe when we use it. */
const MAX_ARGV_PROMPT_BYTES = 16 * 1024;
const MAX_HISTORY_MESSAGES = 30;
const MAX_NOTE_BYTES = 8000;
const MAX_SINGLE_MESSAGE_BYTES = 12000;

function byteLen(s) {
    return Buffer.byteLength(String(s || ''), 'utf8');
}

function clipToBytes(text, maxBytes) {
    const s = String(text || '');
    if (maxBytes <= 0) return '';
    if (byteLen(s) <= maxBytes) return s;
    // Keep the tail — recent tokens matter more for continuation.
    let lo = 0;
    let hi = s.length;
    while (lo < hi) {
        const mid = Math.ceil((lo + hi) / 2);
        const slice = s.slice(s.length - mid);
        if (byteLen('…' + slice) <= maxBytes) lo = mid;
        else hi = mid - 1;
    }
    return '…' + s.slice(s.length - lo);
}

function compactContent(content) {
    let s = String(content || '');
    // Thinking traces dominate long-chat payloads and are not needed to continue.
    s = s.replace(/<thinking>[\s\S]*?<\/thinking>/gi, '');
    s = s.replace(/\n{3,}/g, '\n\n').trim();
    if (byteLen(s) > MAX_SINGLE_MESSAGE_BYTES) {
        s = clipToBytes(s, MAX_SINGLE_MESSAGE_BYTES);
    }
    return s;
}

function normalizeHistory(history) {
    if (!Array.isArray(history)) return [];
    const out = [];
    for (const msg of history) {
        if (!msg || typeof msg !== 'object') continue;
        const role = String(msg.role || '').toLowerCase();
        if (role !== 'user' && role !== 'assistant' && role !== 'system') continue;
        const content = compactContent(msg.content);
        if (!content) continue;
        out.push({ role, content });
    }
    return out;
}

function normalizeNotes(notes) {
    if (!Array.isArray(notes)) return [];
    return notes
        .map((n) => {
            const s = String(n || '').trim();
            if (!s) return '';
            return byteLen(s) > MAX_NOTE_BYTES ? clipToBytes(s, MAX_NOTE_BYTES) : s;
        })
        .filter(Boolean);
}

function buildPromptWithHistory(prompt, history, notes) {
    let ctx = 'When you reply, write only your new answer. Do not repeat prior lines unless asked.\n\n';
    if (notes && notes.length) {
        ctx += '<additional_notes>\n';
        for (const n of notes) ctx += `- ${n}\n`;
        ctx += '</additional_notes>\n\n';
    }
    if (history && history.length) {
        ctx += '<conversation_history>\n';
        for (const msg of history) {
            const role = msg.role === 'user' ? 'User' : (msg.role === 'system' ? 'System' : 'Assistant');
            ctx += `[${role}]: ${msg.content || ''}\n\n`;
        }
        ctx += '</conversation_history>\n\n';
    }
    ctx += `[User]: ${prompt}`;
    return ctx;
}

function fitPromptContext(prompt, history, notes, maxBytes = MAX_PROMPT_BYTES) {
    const userPrompt = String(prompt || '');
    let hist = normalizeHistory(history).slice(-MAX_HISTORY_MESSAGES);
    let nts = normalizeNotes(notes);
    let text = buildPromptWithHistory(userPrompt, hist, nts);
    const historyIn = Array.isArray(history) ? history.length : 0;

    while (byteLen(text) > maxBytes && hist.length > 0) {
        hist = hist.slice(1);
        text = buildPromptWithHistory(userPrompt, hist, nts);
    }
    while (byteLen(text) > maxBytes && nts.length > 0) {
        nts = nts.slice(1);
        text = buildPromptWithHistory(userPrompt, hist, nts);
    }
    if (byteLen(text) > maxBytes) {
        const marker = `[User]: ${userPrompt}`;
        const budget = Math.max(512, maxBytes - byteLen(marker) - 8);
        const prefixEnd = text.lastIndexOf('[User]:');
        const prefix = prefixEnd >= 0 ? text.slice(0, prefixEnd) : '';
        text = clipToBytes(prefix, budget) + marker;
        if (byteLen(text) > maxBytes) {
            text = clipToBytes(text, maxBytes);
        }
    }

    return {
        text,
        historyKept: hist.length,
        historyIn,
        notesKept: nts.length,
        bytes: byteLen(text),
    };
}

function shouldUsePromptFile(promptText, imageCount) {
    if (imageCount > 0) return true;
    return byteLen(promptText) > MAX_ARGV_PROMPT_BYTES;
}

module.exports = {
    MAX_PROMPT_BYTES,
    MAX_ARGV_PROMPT_BYTES,
    MAX_HISTORY_MESSAGES,
    MAX_NOTE_BYTES,
    MAX_SINGLE_MESSAGE_BYTES,
    byteLen,
    clipToBytes,
    compactContent,
    normalizeHistory,
    buildPromptWithHistory,
    fitPromptContext,
    shouldUsePromptFile,
};
