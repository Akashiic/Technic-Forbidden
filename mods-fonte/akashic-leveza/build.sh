#!/usr/bin/env bash
# Compila o Akashic Leveza sem Gradle: javac --release 8 contra o Forge do próprio pack.
#   ./build.sh                 -> build/Akashic-Leveza-<versão>-client.jar
#   FORGE_JAR=/caminho/modpack.jar ./build.sh
#   MC_JAR=.../bin/minecraft.jar [DRAGONAPI_JAR=.../DragonAPI.jar] [MODS_DIR=.../mods PERFIL=leve] ./build.sh test
set -euo pipefail
cd "$(dirname "$0")"

VERSION=$(sed -n 's/.*VERSION = "\(.*\)";/\1/p' src/main/java/br/com/redeakashic/leveza/LevezaMod.java)
FORGE_JAR=${FORGE_JAR:-../../bin/modpack.jar}
LIBS=build/libs
mkdir -p "$LIBS" build/classes

fetch() { # url destino
  [ -f "$2" ] || curl -sSfL -o "$2" "$1"
}
fetch https://repo1.maven.org/maven2/org/ow2/asm/asm-all/5.0.3/asm-all-5.0.3.jar "$LIBS/asm-all-5.0.3.jar"
fetch https://libraries.minecraft.net/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar "$LIBS/launchwrapper-1.12.jar"
fetch https://libraries.minecraft.net/org/apache/logging/log4j/log4j-api/2.0-beta9/log4j-api-2.0-beta9.jar "$LIBS/log4j-api-2.0-beta9.jar"

[ -f "$FORGE_JAR" ] || { echo "Forge não encontrado: $FORGE_JAR (defina FORGE_JAR)"; exit 1; }
CP="$FORGE_JAR:$LIBS/asm-all-5.0.3.jar:$LIBS/launchwrapper-1.12.jar:$LIBS/log4j-api-2.0-beta9.jar"

rm -rf build/classes && mkdir -p build/classes
javac --release 8 -nowarn -encoding UTF-8 -cp "$CP" -d build/classes $(find src/main/java -name '*.java')
sed "s/\${version}/$VERSION/" src/main/resources/mcmod.info > build/classes/mcmod.info
sed "s/\${version}/$VERSION/" src/main/resources/META-INF/MANIFEST.MF > build/MANIFEST.MF
JAR="build/Akashic-Leveza-$VERSION-client.jar"
rm -f "$JAR"
(cd build/classes && jar cfm "../$(basename "$JAR")" ../MANIFEST.MF .)
echo "OK: $JAR"

if [ "${1:-}" = "test" ]; then
  : "${MC_JAR:?defina MC_JAR com o bin/minecraft.jar do pack}"
  fetch https://libraries.minecraft.net/org/lwjgl/lwjgl/lwjgl/2.9.1/lwjgl-2.9.1.jar "$LIBS/lwjgl-2.9.1.jar"
  fetch https://libraries.minecraft.net/com/google/guava/guava/17.0/guava-17.0.jar "$LIBS/guava-17.0.jar"
  TCP="$CP:$LIBS/lwjgl-2.9.1.jar:$LIBS/guava-17.0.jar:build/classes"
  rm -rf build/test-classes && mkdir -p build/test-classes
  javac --release 8 -nowarn -encoding UTF-8 -cp "$TCP" -d build/test-classes $(find src/test/java -name '*.java')
  # mapeamento obf -> SRG que o próprio FML usa em runtime
  python3 -I -c "import lzma,zipfile,sys; z=zipfile.ZipFile(sys.argv[1]); open(sys.argv[2],'wb').write(lzma.decompress(z.read('deobfuscation_data-1.7.10.lzma'), format=lzma.FORMAT_ALONE))" "$FORGE_JAR" build/srg.txt
  java -Djava.awt.headless=true -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp "$TCP:build/test-classes" br.com.redeakashic.leveza.LevezaSelfTest \
       "$MC_JAR" build/srg.txt ${DRAGONAPI_JAR:+"$DRAGONAPI_JAR"}
  if [ -n "${MODS_DIR:-}" ]; then
    # varre todas as texturas de blocos/itens do pack pelo loadSprite real, sem e com o mod
    java -Xmx3g -Djava.awt.headless=true -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp "$TCP:build/test-classes" \
         br.com.redeakashic.leveza.LevezaPackScan "$MC_JAR" build/srg.txt "$MODS_DIR" "${PERFIL:-leve}"
  fi
fi
