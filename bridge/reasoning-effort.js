'use strict';

/** grok-4.5 CLI: high | medium | low. grok-4.6 CLI: xhigh | high | medium | low.
 * "none" / "minimal" are rejected by the current Grok Build CLI. */
const EFFORT_LOW_MED_HIGH = ['low', 'medium', 'high'];
const EFFORT_WITH_XHIGH = ['low', 'medium', 'high', 'xhigh'];

function realModelId(model) {
    if (!model || typeof model !== 'string') return '';
    const m = model.trim();
    if (m.startsWith('gb:')) return m.slice(3);
    if (m.startsWith('grok:') && !m.startsWith('grok-')) return m.slice(5);
    return m;
}

/** xhigh is grok-4.6+ only. Unknown / older models get the 4.5 set so the CLI cannot reject spawn. */
function modelSupportsXhigh(model) {
    const id = realModelId(model).toLowerCase();
    const match = id.match(/^grok-(\d+)(?:\.(\d+))?/);
    if (!match) return false;
    const major = parseInt(match[1], 10);
    const minor = parseInt(match[2] || '0', 10);
    return major > 4 || (major === 4 && minor >= 6);
}

function effortsForModel(model) {
    return modelSupportsXhigh(model) ? EFFORT_WITH_XHIGH.slice() : EFFORT_LOW_MED_HIGH.slice();
}

function defaultEffortForModel(model, envDefault) {
    const allowed = effortsForModel(model);
    const env = String(envDefault || '').trim().toLowerCase();
    if (allowed.includes(env)) return env;
    return allowed.includes('xhigh') ? 'xhigh' : 'high';
}

function resolveReasoningEffort(model, requested, envDefault) {
    const allowed = effortsForModel(model);
    const req = String(requested || '').trim().toLowerCase();
    if (allowed.includes(req)) return req;
    return defaultEffortForModel(model, envDefault);
}

function decorateModels(models, envDefault) {
    return (models || []).map((entry) => {
        const base = typeof entry === 'string' ? { id: entry, name: entry } : { ...entry };
        const id = base.id || '';
        const efforts = effortsForModel(id);
        return {
            ...base,
            reasoning_efforts: efforts,
            default_reasoning_effort: defaultEffortForModel(id, envDefault),
        };
    });
}

module.exports = {
    EFFORT_LOW_MED_HIGH,
    EFFORT_WITH_XHIGH,
    realModelId,
    modelSupportsXhigh,
    effortsForModel,
    defaultEffortForModel,
    resolveReasoningEffort,
    decorateModels,
};
