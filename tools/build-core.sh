#!/usr/bin/env bash
# Compila o módulo core (e opcionalmente roda os testes) SEM rede, usando o compilador Kotlin embutido no Gradle.
# Uso: tools/build-core.sh [test]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GL="${GRADLE_LIB:-$(dirname "$(readlink -f "$(which gradle)")")/../lib}"
KC="$GL/kotlin-compiler-embeddable-2.0.21.jar:$GL/kotlin-stdlib-2.0.21.jar:$GL/kotlin-script-runtime-2.0.21.jar:$GL/kotlin-reflect-2.0.21.jar:$GL/kotlin-daemon-embeddable-2.0.21.jar:$GL/trove4j-1.0.20200330.jar:$GL/annotations-24.0.1.jar:$GL/kotlinx-coroutines-core-jvm-1.6.4.jar"
STD="$GL/kotlin-stdlib-2.0.21.jar"
OUT="${OUT_DIR:-$ROOT/build-offline}"
rm -rf "$OUT"; mkdir -p "$OUT/main" "$OUT/test"
kc() { java -Xmx1g -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -nowarn "$@"; }
kc -cp "$STD" -d "$OUT/main" $(find "$ROOT/core/src/main/kotlin" -name '*.kt')
echo "== core compilado =="
if [ "${1:-}" = "test" ]; then
  JUNIT="$GL/junit-4.13.2.jar:$GL/hamcrest-core-1.3.jar"
  kc -cp "$STD:$JUNIT:$OUT/main" -d "$OUT/test" $(find "$ROOT/core/src/test/kotlin" -name '*.kt')
  CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort | tr '\n' ' ')
  java -cp "$STD:$JUNIT:$OUT/main:$OUT/test:$ROOT/core/src/test/resources" org.junit.runner.JUnitCore $CLASSES
fi
