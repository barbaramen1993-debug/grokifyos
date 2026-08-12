'use strict';

const assert = require('assert');
const {
    effortsForModel,
    defaultEffortForModel,
    resolveReasoningEffort,
    modelSupportsXhigh,
    decorateModels,
} = require('./reasoning-effort');

assert.deepStrictEqual(effortsForModel('grok-4.6'), ['low', 'medium', 'high', 'xhigh']);
assert.deepStrictEqual(effortsForModel('gb:grok-4.6'), ['low', 'medium', 'high', 'xhigh']);
assert.deepStrictEqual(effortsForModel('grok-4.5'), ['low', 'medium', 'high']);
assert.deepStrictEqual(effortsForModel('gb:grok-4.5'), ['low', 'medium', 'high']);
assert.deepStrictEqual(effortsForModel('grok-composer-2.5-fast'), ['low', 'medium', 'high']);
assert.strictEqual(modelSupportsXhigh('grok-5'), true);
assert.strictEqual(modelSupportsXhigh('grok-4.5'), false);

assert.strictEqual(defaultEffortForModel('grok-4.6', 'xhigh'), 'xhigh');
assert.strictEqual(defaultEffortForModel('grok-4.5', 'xhigh'), 'high');
assert.strictEqual(defaultEffortForModel('grok-4.5', 'medium'), 'medium');

assert.strictEqual(resolveReasoningEffort('grok-4.5', 'xhigh', 'xhigh'), 'high');
assert.strictEqual(resolveReasoningEffort('grok-4.5', 'low', 'xhigh'), 'low');
assert.strictEqual(resolveReasoningEffort('grok-4.6', 'xhigh', 'high'), 'xhigh');
assert.strictEqual(resolveReasoningEffort('grok-4.6', 'none', 'xhigh'), 'xhigh');
assert.strictEqual(resolveReasoningEffort('grok-4.6', '', 'xhigh'), 'xhigh');

const decorated = decorateModels([{ id: 'grok-4.5', name: 'grok-4.5' }], 'xhigh');
assert.deepStrictEqual(decorated[0].reasoning_efforts, ['low', 'medium', 'high']);
assert.strictEqual(decorated[0].default_reasoning_effort, 'high');

console.log('reasoning-effort tests ok');
