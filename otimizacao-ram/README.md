# Otimização de RAM — Technic Forbidden (TEOF 5.5)

Análise feita em cima do `TEOF-5.5-MANUAL.zip` (225 arquivos em `mods/`, Forge 1614, Java 8).

**Ponto de partida medido** (sessão longa registrada no repositório do Mirai, `docs/PROJECT-STATE.md`): cliente com
`-Xmx6G`, Java **1.8.0_51**, Intel UHD → **pico de heap de 4,71 GiB (78%)**, com o governor do Mirai já cortando workers
por pressão de heap. É isso que hoje impede máquinas com 4 GB ou menos de entrar.

## Resumo

**O FoamFix não resolve o caso de vocês, e a suspeita estava certa.** O FoamFix para 1.7.10 só
"enfraquece" o cache de bytes do LaunchWrapper, e o ArchaicFix (`clearLaunchLoaderCache=true`)
já faz isso. A deduplicação de modelos, que é o que faz o FoamFix famoso no 1.12, não existe no 1.7.10.

**O maior consumo de RAM do pack são as texturas animadas.** No 1.7.10, cada sprite animado do
atlas de blocos/itens guarda **todos os frames, e os mipmaps de cada frame**, como `int[]` no heap
durante a sessão inteira. O ChromatiCraft registra animações de efeitos com frames de **256×256**
e até **180 frames** (o `roundflare.png` tem 256×46080 pixels).

| O que foi medido (leitura dos PNGs dentro dos jars) | Antes | Perfil `leve` | Perfil `batata` |
|---|---:|---:|---:|
| Texturas animadas: heap **residente** a sessão toda (mipmap 4) | **~497 MiB** | ~125 MiB | ~70 MiB |
| …dos quais só o ChromatiCraft | ~441 MiB | — | — |
| Atlas estático: pico no carregamento + VRAM | ~230 MiB | ~168 MiB | ~132 MiB |
| Texturas de entidade/modelo (transiente no heap + VRAM) | ~1412 MiB | ~1066 MiB | ~875 MiB |

Com `mipmapLevels:0` (aplicado nos perfis leve/batata), o que resta das animações cai mais ~25%.

Outros pontos encontrados:

| Item | Situação | Ação |
|---|---|---|
| CoreTweaks: cache de transformers | `liteTransformerCacheMaxSizeMB=256` e `useDiffsInTransformerCache=false`: até 256 MiB presos no heap durante o boot, bem na hora do stitch das texturas | 128 MiB (64 no batata) + diffs ligados (-40 a 70% de tamanho) |
| Hodgepodge | `internResourceLocationDomain` e `internUniqueIdentifierModid` desligados | ligados (ganho pequeno, mas de graça) |
| JourneyMap 6 | mapa topográfico ligado (mais um conjunto de imagens de região na RAM) | desligado nos perfis leve/batata |
| JurassiCraft | texturas de dinossauro com **4096×4096** (64 MiB de `int[]` cada vez que carregam, e mais 64 MiB de VRAM) | reduzidas para 1024 no perfil leve |

## Caminho recomendado: o mod Akashic Leveza

As reduções de textura agora existem como mod: [`mods-fonte/akashic-leveza`](../mods-fonte/akashic-leveza/README.md).
Ele faz o mesmo que o script, só que em memória e na hora do carregamento:
- não gera arquivos e não precisa ser refeito quando um mod atualiza;
- também alcança as texturas do Reika fora de `assets/`, que o script não consegue tocar.

Medido passando as 39.999 texturas de blocos/itens do pack pelo `loadSprite` real do jogo: as animações
caem de **361 → 83 MiB** no perfil `leve` e para **43 MiB** no `batata` (sem mipmaps), com **0** texturas
passando a falhar. O mod ainda precisa ser validado dentro do jogo.

Usando o mod, rode só a parte de configs deste pacote:

```bash
python3 otimizacao-ram/ferramentas/aplicar_otimizacoes.py "CAMINHO/.minecraft" --perfil leve --sem-texturas
```

## Alternativa sem mod: o script (no PC onde vocês montam o pack)

Requer Python 3.8+ e Pillow (`pip install pillow`).

```bash
python3 otimizacao-ram/ferramentas/aplicar_otimizacoes.py "CAMINHO/.minecraft" --perfil leve
```

- Para medir antes, sem gravar nada, adicione `--simular`.
- Para mudar de perfil, é só rodar de novo com outro `--perfil`: a geração anterior é substituída.
- Para desfazer as texturas:

  ```bash
  python3 otimizacao-ram/ferramentas/otimizar_texturas.py "CAMINHO/.minecraft" --limpar
  ```

  Os configs alterados ganham backup `*.bak-otimizacao` na primeira alteração.

Depois é só empacotar o zip do Technic normalmente: as texturas geradas ficam em
`config/txloader/load/` (cerca de 16 MB no perfil leve) e entram junto no pack.

### Perfis

| Perfil | Frame animado | Frames máx. | Ícone estático | Textura de modelo em blocks/items | Entidades/efeitos | Para quem |
|---|---:|---:|---:|---:|---:|---|
| `qualidade` | 128 | sem corte | 128 | 1024 | 2048 | PC bom; só corta os exageros |
| `equilibrado` | 64 | sem corte | 64 | 512 | 2048 | PC médio |
| **`leve`** (padrão) | 64 | 64 | 64 | 256 | 1024 | PC fraco / notebook com vídeo integrado |
| `batata` | 32 | 32 | 32 | 128 | 512 | celular (Pojav etc.) / PC muito fraco |

Quando uma animação tem mais frames que o limite, a ferramenta mantém 1 a cada N frames e
multiplica o `frametime` por N. Assim a duração da animação continua a mesma; ela só fica menos fluida.

### Como funciona (e por que é seguro)

- **Nenhum jar é modificado.** A ferramenta lê as texturas dos jars e grava cópias reduzidas em
  `config/txloader/load/<dominio>/…`. O TX Loader, que já está no pack, carrega essa pasta como
  resource pack **acima dos mods e abaixo dos resource packs do jogador**.
- A redução usa só fatores de 2 (filtro de média por área), então as proporções e as UVs continuam
  exatas. Nada fica menor que 16px, e um sprite múltiplo de 16 nunca vira um tamanho que não seja
  múltiplo de 16. Assim as folhas de CTM do Chisel (grades de 16px) e o nível de mipmap do atlas ficam intactos.
- O `.mcmeta` sempre acompanha o PNG gerado. Isso é obrigatório: o resource manager do 1.7.10 só
  procura o `.mcmeta` no pack que forneceu o PNG ou acima dele.
- O que **não** é tocado:
  - domínios `minecraft`, `akashiccinematic`, `distanthorizons`;
  - caminhos com `font`, `gui`, `title`, `splash` e `alphabet` (o código costuma depender das dimensões em pixel deles);
  - assets que aparecem em mais de um jar (não dá para saber qual vence);
  - sprites estáticos não quadrados (o 1.7.10 já os recusa).
- **Limitação:** texturas carregadas fora de `assets/` não passam por resource pack e não podem ser
  sobrescritas assim. É o caso de `Reika/ChromatiCraft/Textures/…` (~97 M pixels, ex.: `256x.png`
  4096², `aurora4.png`). Elas pesam mais na VRAM do que no heap. Para reduzi-las seria preciso um
  patch no jar ou um coremod (ver "Próximos passos").

## Mirai (Distant Horizons)

O Mirai não é tocado por este pacote: ele é desenvolvido e configurado no próprio projeto
([Akashiic/Mirai](https://github.com/Akashiic/Mirai)).

- A build instalada no 5.5 é a `0.1.19-dev` "disabled-renderer quiescence". Com `rendererMode = "DISABLED"`, ela já não cria
  `ClientRenderState`/quadtree, não ingere chunk→LOD e não faz upload GL. Por isso, desligado, o custo dele é pequeno.
- A ferramenta de texturas ignora o domínio `distanthorizons`.
- **A otimização de texturas ajuda diretamente o Mirai.** O governor dele tira workers quando o heap passa de ~74%. As
  ~370 MiB de frames animados liberados (perfil leve) são folga que volta para o renderer de LOD quando ele estiver
  ativo. Vale repetir a medição G10 (sessão longa) do Mirai depois de aplicar, para comparar com os 4,71 GiB.
- A 1.0-BETA já transforma em opt-in o `System.gc()` ao fechar o mundo e para de vazar executores do picker entre sessões.
  As duas mudanças também ajudam na RAM quando forem homologadas.

## Argumentos de Java (Technic → Opções do Launcher → Java)

Para **Java 8** (o que o Technic usa no 1.7.10), cole em "Argumentos JVM adicionais":

```
-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=8M -XX:+UseStringDeduplication -XX:+ParallelRefProcEnabled
```

- `-XX:+UseStringDeduplication` é o item que mais economiza aqui: o modded 1.7.10 tem dezenas de
  MB de Strings repetidas (chaves de NBT, nomes de recurso, lang). Só funciona com G1 e Java 8u20+.
- Prefiram um Java 8 atual (Temurin/Zulu 8u4xx). O log de referência mostra o **1.8.0_51** de 2015, e o G1 melhorou muito
  desde então. No Technic: Opções do Launcher → Java → escolher o executável do Java 8 novo.
- **Não** coloquem `-Xms` igual ao `-Xmx` em máquina fraca: isso reserva toda a RAM logo no início.

## Como medir (antes × depois)

As economias acima são calculadas a partir do tamanho real de cada PNG, não de uma sessão de jogo
medida. Para confirmar no jogo:

1. Usem a mesma memória máxima no Technic nos dois testes (ex.: 4 GB).
2. Entrem no mundo, esperem 1 a 2 minutos parados no spawn e apertem **F3**. Anotem o "Mem: X% Y/Z MB".
3. Para ver o detalhe por classe (com o JDK instalado), rodem `jcmd <pid> GC.class_histogram | head -30`.
   Antes da otimização, `[I` (int[]) deve aparecer no topo por causa dos frames de textura.
4. Testem também o pico: abaixem o `-Xmx` até o jogo dar `OutOfMemoryError` no carregamento.
   Esse valor mínimo é o que define se um PC ou celular fraco consegue entrar.

## Próximos passos (impacto grande, mas exigem teste)

1. **Java moderno (17/21/25) com lwjgl3ify**, como faz o GTNH. Dá acesso a GCs melhores e, no Java
   24+, a `-XX:+UseCompactObjectHeaders`, que corta ~10 a 20% de todo o heap. Já existem
   `lwjgl3ify.cfg`/`lwjgl3ify-early.json` sobrando no `config/`, sinal de que isso já foi tentado.
   Os mods do Reika (DragonAPI) e o OreSpawn são os de maior risco de incompatibilidade.
2. ~~Coremod para as texturas `Reika/ChromatiCraft/Textures`~~: feito no Akashic Leveza (`reduzir.reika`).
3. **Revisar os mods que se sobrepõem**, como JurassiCraft + Fossils (dois mods de dinossauro com
   modelos e texturas enormes) e Opis no cliente (é um profiler de servidor, ~3.500 classes).
4. Remover configs órfãos de mods que já saíram do pack (`lwjgl3ify*`, `FpsReducer/`,
   `entityculling.json`, `bogosorter*`). Isso não economiza RAM, só organiza.
