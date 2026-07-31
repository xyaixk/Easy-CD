import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildDockerImageReference,
  isValidDockerImageTag,
  parseDockerImageReference
} from './dockerImage.js'

test('parseDockerImageReference parses regular and untagged images', () => {
  assert.deepEqual(parseDockerImageReference('nginx:1.27.0'), {
    repository: 'nginx',
    tag: '1.27.0'
  })
  assert.deepEqual(parseDockerImageReference('library/nginx'), {
    repository: 'library/nginx',
    tag: 'latest'
  })
})

test('parseDockerImageReference preserves registry ports and removes digests', () => {
  assert.deepEqual(
    parseDockerImageReference('registry.example.com:5000/project/app:1.2.3@sha256:abcdef'),
    {
      repository: 'registry.example.com:5000/project/app',
      tag: '1.2.3'
    }
  )
  assert.deepEqual(
    parseDockerImageReference('registry.example.com:5000/project/app@sha256:abcdef'),
    {
      repository: 'registry.example.com:5000/project/app',
      tag: 'latest'
    }
  )
})

test('isValidDockerImageTag follows Docker tag syntax and length limit', () => {
  assert.equal(isValidDockerImageTag('release_1.2-rc.1'), true)
  assert.equal(isValidDockerImageTag('_temporary'), true)
  assert.equal(isValidDockerImageTag('release/latest'), false)
  assert.equal(isValidDockerImageTag('release:latest'), false)
  assert.equal(isValidDockerImageTag(' latest'), false)
  assert.equal(isValidDockerImageTag('a'.repeat(129)), false)
})

test('buildDockerImageReference replaces only the tag and strips the old digest', () => {
  assert.equal(
    buildDockerImageReference(
      'registry.example.com:5000/project/app:1.2.3@sha256:abcdef',
      '2.0.0'
    ),
    'registry.example.com:5000/project/app:2.0.0'
  )
  assert.equal(
    buildDockerImageReference('registry.example.com:5000/project/app', ' latest '),
    'registry.example.com:5000/project/app:latest'
  )
})

test('buildDockerImageReference rejects missing images and invalid tags', () => {
  assert.throws(
    () => buildDockerImageReference('', 'latest'),
    /当前镜像地址不能为空/
  )
  assert.throws(
    () => buildDockerImageReference('nginx:latest', 'release/latest'),
    /镜像 Tag 格式不正确/
  )
})
