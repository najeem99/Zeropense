// Gradle settings for faster local builds, applied on `expo prebuild` so they survive regeneration.
const { withGradleProperties } = require('expo/config-plugins');

const PROPS = {
  // Reuse task outputs across builds and branches instead of recompiling.
  'org.gradle.caching': 'true',
  // R8 and the C++ builds run out of the default 2 GB heap and slow down on GC.
  'org.gradle.jvmargs': '-Xmx4096m -XX:MaxMetaspaceSize=1024m',
};

module.exports = function withGradleSpeed(config) {
  return withGradleProperties(config, (cfg) => {
    for (const [key, value] of Object.entries(PROPS)) {
      const existing = cfg.modResults.find((p) => p.type === 'property' && p.key === key);
      if (existing) existing.value = value;
      else cfg.modResults.push({ type: 'property', key, value });
    }
    return cfg;
  });
};
