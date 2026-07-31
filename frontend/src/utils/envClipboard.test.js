import test from 'node:test'
import assert from 'node:assert/strict'
import {
  EnvClipboardError,
  applyEnvMerge,
  parseEnvText,
  prepareEnvMerge,
  serializeEnvList
} from './envClipboard.js'

test('parseEnvText supports common .env syntax', () => {
  const result = parseEnvText(`
    # comment
    export API_URL=https://example.test/query?a=1
    EMPTY=
    QUOTED="two words # kept"
    SINGLE='literal value'
    HASH=abc#123
  `)

  assert.deepEqual(result, [
    { key: 'API_URL', value: 'https://example.test/query?a=1' },
    { key: 'EMPTY', value: '' },
    { key: 'QUOTED', value: 'two words # kept' },
    { key: 'SINGLE', value: 'literal value' },
    { key: 'HASH', value: 'abc#123' }
  ])
})

test('serializeEnvList preserves effective values and round trips special characters', () => {
  const envList = [
    { key: 'OLD', value: 'first' },
    { key: '', value: 'unfinished' },
    { key: 'SPACE', value: 'two words' },
    { key: 'HASH', value: 'abc#123' },
    { key: 'OLD', value: 'last' },
    { key: 'EMPTY', value: '' }
  ]

  const serialized = serializeEnvList(envList)
  assert.equal(
    serialized,
    'SPACE="two words"\nHASH="abc#123"\nOLD=last\nEMPTY='
  )
  assert.deepEqual(parseEnvText(serialized), [
    { key: 'SPACE', value: 'two words' },
    { key: 'HASH', value: 'abc#123' },
    { key: 'OLD', value: 'last' },
    { key: 'EMPTY', value: '' }
  ])
})

test('parseEnvText rejects the whole input with an actionable line number', () => {
  assert.throws(
    () => parseEnvText('VALID=1\nBROKEN_LINE\nOTHER=2'),
    error => error instanceof EnvClipboardError
      && error.lineNumber === 2
      && error.message.includes('KEY=VALUE')
  )
  assert.throws(
    () => parseEnvText('DUP=1\nDUP=2'),
    error => error.lineNumber === 2 && error.message.includes('首次出现在第 1 行')
  )
  assert.throws(
    () => parseEnvText('network=overlay'),
    error => error.lineNumber === 1 && error.message.includes('Docker 内置参数')
  )
  assert.throws(
    () => parseEnvText('BAD KEY=value'),
    error => error.lineNumber === 1 && error.message.includes('不合法')
  )
})

test('prepareEnvMerge classifies additions, unchanged values, and conflicts', () => {
  const plan = prepareEnvMerge(
    [
      { key: 'CURRENT', value: 'old' },
      { key: 'SAME', value: 'value' }
    ],
    [
      { key: 'CURRENT', value: 'new' },
      { key: 'SAME', value: 'value' },
      { key: 'ADDED', value: 'created' }
    ]
  )

  assert.deepEqual(plan.additions, [{ key: 'ADDED', value: 'created' }])
  assert.deepEqual(plan.unchanged, [{ key: 'SAME', value: 'value' }])
  assert.deepEqual(plan.conflicts, [{
    key: 'CURRENT',
    currentValue: 'old',
    incomingValue: 'new',
    resolution: 'current'
  }])
})

test('applyEnvMerge applies mixed resolutions atomically and collapses touched duplicates', () => {
  const current = [
    { key: 'KEEP', value: 'untouched' },
    { key: 'FIRST', value: 'stale duplicate' },
    { key: 'FIRST', value: 'effective current' },
    { key: 'SECOND', value: 'current second' },
    { key: '', value: '' }
  ]
  const plan = prepareEnvMerge(current, [
    { key: 'FIRST', value: 'incoming first' },
    { key: 'SECOND', value: 'incoming second' },
    { key: 'NEW', value: 'new value' }
  ])

  const result = applyEnvMerge(current, plan, {
    FIRST: 'incoming',
    SECOND: 'current'
  })

  assert.deepEqual(result, [
    { key: 'KEEP', value: 'untouched' },
    { key: 'FIRST', value: 'incoming first' },
    { key: 'SECOND', value: 'current second' },
    { key: '', value: '' },
    { key: 'NEW', value: 'new value' }
  ])
  assert.deepEqual(current[1], { key: 'FIRST', value: 'stale duplicate' })
})
