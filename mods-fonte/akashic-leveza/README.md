# Akashic Leveza

O "FoamFix" do Technic Forbidden: um coremod **só de cliente** para Forge 1.7.10 que reduz a RAM e a
VRAM das texturas **na hora em que são carregadas**. Não altera nenhum arquivo e não precisa
regenerar nada quando um mod atualiza.

> Status: `0.1.0-alpha.1`. O bytecode e a lógica foram testados offline contra as classes reais do
> jogo (ver "Testes"), mas **ainda não rodou dentro do Minecraft**. Siga o roteiro de validação abaixo
> antes de colocar no pack público.

## Por que não o FoamFix

O FoamFix do 1.7.10 só enfraquece o cache de classes do LaunchWrapper, e o ArchaicFix já faz isso.
No TEOF, o que pesa são as **texturas animadas**: no 1.7.10 cada sprite animado guarda todos os
frames, e os mipmaps de cada frame, no heap durante a sessão inteira. Só o ChromatiCraft registra
animações de 256×256 com até 180 frames.

## O que ele faz

| Gancho (início do método, sem mexer em call sites) | Efeito |
|---|---|
| `TextureAtlasSprite.loadSprite` | **Animações:** reduz a resolução dos frames e, se houver frames demais, mantém 1 a cada N e multiplica o `frametime` por N (a duração não muda). **Ícones e texturas de modelo estáticos:** reduz até o limite do perfil. |
| `TextureUtil.uploadTextureImageAllocate` (+ `SimpleTexture.loadTexture` como contexto) | Limita texturas avulsas gigantes (ex.: dinossauros 4096² do JurassiCraft), **só** quando o upload vem de um `loadTexture`. |
| `ReikaTextureBinder.allocateAndSetupTexture` / `loadImageOntoTexture` | Limita as texturas do Reika fora de `assets/` (ChromatiCraft `Textures/…`: 4096², auroras etc.), que nenhum resource pack alcança. |

A redução usa só fatores de 2, com média por área e alfa pré-multiplicado. Regras de segurança:
- Um sprite múltiplo de 16 nunca vira um tamanho que não seja múltiplo de 16. Assim as folhas de CTM do Chisel e o nível de mipmap do atlas ficam intactos.
- Animações com lista explícita de `frames` no `.mcmeta` só perdem resolução; nunca perdem frames.
- Sprites não quadrados sem `.mcmeta` não são tocados (o vanilla já os recusa).
- Os domínios `minecraft`, `akashiccinematic` e `distanthorizons` (Mirai) são ignorados por padrão.
- Qualquer erro dentro do mod é registrado uma única vez no log, e a textura segue original. O mod nunca derruba o jogo.

## Resultado medido (todas as texturas de blocos/itens do TEOF 5.5)

Teste `LevezaPackScan`: as 39.999 texturas de blocos e itens dos jars passaram pelo `loadSprite` real
do jogo, primeiro sem o mod e depois com ele. Os valores são heap após o `loadSprite`, sem mipmaps.

| | Sem o mod | `leve` (padrão) | `batata` |
|---|---:|---:|---:|
| Animações (residentes a sessão toda) | 361 MiB | **83 MiB** | **43 MiB** |
| Tudo após `loadSprite` (pico do carregamento) | 490 MiB | 166 MiB | 99 MiB |
| Texturas que passaram a falhar por causa do mod | — | **0** | **0** |

Com `mipmapLevels:4`, some ~33% a todos os números. Nas texturas animadas isso dá ~481 MiB → ~111 MiB no `leve`.

## E o boot?

Medido com o `LevezaPackScan` (as 39.999 texturas de blocos/itens, 1 thread, CPU de nuvem rápida):

| Etapa das texturas no boot | Sem o mod | `leve` | `batata` |
|---|---:|---:|---:|
| Decodificar os PNGs (o mod não altera; o Angelica paraleliza) | 10,6 s | 10,6 s | 10,7 s |
| `loadSprite` + redução + mipmaps nível 4 | 5,5 s | 4,9 s | 3,7 s |

**Em PC com RAM sobrando, o ganho de velocidade é pequeno**: 1 a 2 s em CPU rápida (proporcionalmente
mais em CPU fraca). O PNG continua sendo decodificado em tamanho cheio antes da redução.

**Em PC com o heap apertado, a diferença é grande.** Simulação do stitch com todos os sprites vivos ao
mesmo tempo, como no jogo:

| `-Xmx` só para as texturas | Sem o mod | Com o mod (`leve`) |
|---|---|---|
| 1000 MB | 22,1 s, 0 GC completo | 20,2 s, 0 GC completo |
| 700 MB | 19,2 s, **3 GC completos** | 19,3 s, 0 GC completo |
| 500 MB | **OutOfMemoryError** depois de 193 GCs completos | 21,2 s, 0 GC completo |

No jogo, essas texturas disputam o heap com o resto dos mods. Por isso, quem está perto do limite de
RAM tem hoje um boot arrastado (GC completo atrás de GC completo) ou um crash. Com o mod, isso deixa
de acontecer na fase das texturas. Atlas menores também sobem mais rápido para a GPU, mas isso só
dá para medir no jogo.

## Instalação

1. Copie `dist/Akashic-Leveza-<versão>-client.jar` para `mods/` (só no cliente; o servidor não precisa).
2. Abra o jogo uma vez. Ele cria `config/akashic-leveza.properties`.
3. Se vocês usaram o script `otimizacao-ram/ferramentas/otimizar_texturas.py`, desfaçam as texturas geradas
   (o mod substitui o script):

   ```bash
   python3 otimizacao-ram/ferramentas/otimizar_texturas.py "CAMINHO/.minecraft" --limpar
   ```

   Os ajustes de config continuam valendo:

   ```bash
   python3 otimizacao-ram/ferramentas/aplicar_otimizacoes.py "CAMINHO/.minecraft" --sem-texturas
   ```

## Configuração (`config/akashic-leveza.properties`)

| Perfil | Frame animado | Frames máx. | Ícone estático | Modelo no atlas | Texturas avulsas / Reika |
|---|---:|---:|---:|---:|---:|
| `qualidade` | 128 | todos | 128 | 1024 | 2048 |
| `equilibrado` | 64 | todos | 64 | 512 | 2048 |
| **`leve`** | 64 | 64 | 64 | 256 | 1024 |
| `batata` | 32 | 32 | 32 | 128 | 512 |
| `desligado` | — | — | — | — | — |

- O jogador pode trocar de perfil sem editar o arquivo, com `-Dakashic.leveza.perfil=batata` nos argumentos de Java.
- Há ajuste fino por chave (`anim.largura.max`, `anim.frames.max`, `estatico.max`, `modelo.max`, `outros.max`).
- Cada parte liga e desliga sozinha (`reduzir.atlas`, `reduzir.texturas.grandes`, `reduzir.reika`).
- Listas de exceção: `ignorar.dominios` e `ignorar.texturas`, por prefixo (ex.: `chromaticraft:icons/portalring`).
- `log.detalhado=true` lista cada textura reduzida.

## Roteiro de validação no jogo

1. **Log de boot (`fml-client-latest.log`):** devem aparecer 4 linhas `[AkashicLeveza] Gancho instalado em …`:
   - `TextureAtlasSprite`
   - `SimpleTexture`
   - `TextureUtil`
   - `ReikaTextureBinder`

   Depois deve aparecer uma linha `Perfil leve: …`.
2. **Após o carregamento:** deve aparecer uma linha `Atlas: N de M sprites reduzidos | animações X -> Y MiB …` por atlas.
3. **No mundo:** ver os efeitos do ChromatiCraft (pylons, portal, flares), blocos do Chisel com CTM, um dinossauro
   do JurassiCraft e um livro/guia do ChromatiCraft.
4. **Medição:** repetir a sessão longa do Mirai (G10) e comparar com o pico de 4,71 GiB de 6 GiB. Também vale
   testar o menor `-Xmx` que ainda carrega o pack.
5. **Se algo ficar estranho:** ponha o prefixo da textura em `ignorar.texturas`, ou desligue só a parte responsável.

## Compilar e testar

Não precisa de Gradle: compila com `javac --release 8` contra o Forge do próprio pack (`bin/modpack.jar`).

```bash
./build.sh
```

```bash
MC_JAR=.../bin/minecraft.jar DRAGONAPI_JAR=".../mods/DragonAPI 1.7.10 V33b-ForbiddenFix1.jar" MODS_DIR=.../mods ./build.sh test
```

Os testes:
- remapeiam o `minecraft.jar` real para nomes SRG com o `deobfuscation_data` do próprio FML;
- aplicam o transformer e verificam o bytecode (`CheckClassAdapter`) de `TextureAtlasSprite`, `TextureUtil`, `SimpleTexture` e `ReikaTextureBinder`;
- executam o `loadSprite` real (`LevezaSelfTest`, 26 verificações);
- com `MODS_DIR`, passam todas as texturas do pack pelo jogo com e sem o mod (`LevezaPackScan`).

### Compatibilidade verificada no bytecode

- **Angelica 2.2.43:**
  - A carga paralela de atlas só pré-decodifica a imagem; o vanilla continua chamando `loadSprite`, e é ali que o mod atua.
  - O mixin de mipmaps do Angelica guarda uma cópia temporária da tira inteira de cada animação; com a imagem já reduzida, essa cópia também encolhe.
  - Nenhum mixin do Angelica nem do Hodgepodge mexe em `TextureUtil.uploadTextureImageAllocate`.
- **Akashic Angelica Atlas Heap Fix / Chroma Heap Fixes:** atuam em outras classes, sem sobreposição.

Licença: a mesma dos demais mods da Rede Akashic (definir).
