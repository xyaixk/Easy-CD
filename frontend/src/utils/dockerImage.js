const DOCKER_IMAGE_TAG_PATTERN = /^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$/

export function parseDockerImageReference(imageReference) {
  const normalizedReference = String(imageReference ?? '').trim()
  if (!normalizedReference) {
    return { repository: '', tag: 'latest' }
  }

  const digestIndex = normalizedReference.indexOf('@')
  const referenceWithoutDigest = digestIndex >= 0
    ? normalizedReference.substring(0, digestIndex)
    : normalizedReference
  const lastSlashIndex = referenceWithoutDigest.lastIndexOf('/')
  const lastColonIndex = referenceWithoutDigest.lastIndexOf(':')

  if (lastColonIndex > lastSlashIndex) {
    return {
      repository: referenceWithoutDigest.substring(0, lastColonIndex),
      tag: referenceWithoutDigest.substring(lastColonIndex + 1) || 'latest'
    }
  }

  return {
    repository: referenceWithoutDigest,
    tag: 'latest'
  }
}

export function isValidDockerImageTag(tag) {
  return DOCKER_IMAGE_TAG_PATTERN.test(String(tag ?? ''))
}

export function buildDockerImageReference(currentImage, newTag) {
  const { repository } = parseDockerImageReference(currentImage)
  const normalizedTag = String(newTag ?? '').trim()

  if (!repository) {
    throw new Error('当前镜像地址不能为空')
  }
  if (!isValidDockerImageTag(normalizedTag)) {
    throw new Error('镜像 Tag 格式不正确')
  }

  return `${repository}:${normalizedTag}`
}
