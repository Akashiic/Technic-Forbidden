#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Otimizador de texturas do Technic Forbidden (TEOF) — reduz RAM e VRAM sem tocar nos jars.

O que faz
---------
Lê as texturas de dentro dos jars em ``.minecraft/mods`` e grava versões reduzidas em
``.minecraft/config/txloader/load/<dominio>/...``. O TX Loader (já presente no pack) carrega
essa pasta como um resource pack que fica ACIMA dos mods e ABAIXO dos resource packs do
jogador. Nenhum jar é alterado; para desfazer, basta rodar com ``--limpar``.

Por que isso economiza RAM
--------------------------
No 1.7.10 cada sprite ANIMADO do atlas de blocos/itens guarda TODOS os frames (e os mipmaps
de cada frame) como int[] no heap durante a sessão inteira. O ChromatiCraft registra dezenas
de animações de efeitos com frames de 256x256 e até 180 frames cada (ex.: roundflare.png é
256x46080): sozinho são ~86 milhões de pixels => ~330 MiB de heap (~440 MiB com mipmap 4).
Texturas estáticas do atlas são liberadas depois do upload, mas inflam o pico de RAM no
carregamento e o tamanho do atlas na VRAM. Texturas gigantes de entidades (ex.: 4096x4096
do JurassiCraft) geram picos de 64 MiB no heap a cada carregamento e 64 MiB+ de VRAM.

Uso
---
    python3 otimizar_texturas.py CAMINHO/.minecraft --simular            # só mede, não grava
    python3 otimizar_texturas.py CAMINHO/.minecraft --perfil leve        # grava (padrão: leve)
    python3 otimizar_texturas.py CAMINHO/.minecraft --limpar             # remove o que foi gerado
    python3 otimizar_texturas.py CAMINHO/.minecraft --perfil batata -v   # outro perfil, com detalhes

Perfis: qualidade, equilibrado, leve (padrão), batata (celular/PC muito fraco).
Rodar de novo com outro perfil substitui a geração anterior automaticamente.

Requer Python 3.8+ e Pillow (``pip install pillow``).
"""
import argparse
import io
import json
import math
import os
import re
import struct
import sys
import zipfile

PERFIS = {
    # anim: largura máx. do frame animado | frames: nº máx. de frames por animação
    # estatico: lado máx. de ícone estático do atlas (até 128px)
    # modelo: lado máx. de texturas >128px dentro de textures/blocks|items (texturas de modelo 3D)
    # outros: lado máx. das demais texturas (entidades, modelos, efeitos)
    "qualidade":   dict(anim=128, frames=9999, estatico=128, modelo=1024, outros=2048),
    "equilibrado": dict(anim=64,  frames=9999, estatico=64,  modelo=512,  outros=2048),
    "leve":        dict(anim=64,  frames=64,   estatico=64,  modelo=256,  outros=1024),
    "batata":      dict(anim=32,  frames=32,   estatico=32,  modelo=128,  outros=512),
}

# Domínios que nunca devem ser tocados (fontes com métricas em pixel, menus nativos, vanilla).
DOMINIOS_IGNORADOS = {"minecraft", "akashiccinematic", "distanthorizons", "fml", "forge"}
# Trechos de caminho onde as dimensões em pixel costumam ser usadas pelo código.
CAMINHOS_IGNORADOS = ("/font", "/gui", "/title/", "/splash", "alphabet")

MANIFESTO = os.path.join("config", "akashic-otimizacao-texturas.json")
PASTA_SAIDA = os.path.join("config", "txloader", "load")

PADRAO_TEXTURA = re.compile(r"^assets/([^/]+)/(textures/.+\.png)$", re.I)
PADRAO_ATLAS = re.compile(r"^textures/(blocks|items)/", re.I)


def dim_png(dados):
    if dados[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return struct.unpack(">II", dados[16:24])


def fator_pot2(lado, limite, minimo=16):
    """Menor fator 2^n tal que lado/fator <= limite, sem passar do mínimo e mantendo divisão exata."""
    # Lado múltiplo de 16 nunca vira lado não múltiplo de 16 (folhas de CTM e nível de mipmap do atlas).
    f = 1
    piso = min(minimo, limite)
    alinhado = lado % 16 == 0
    while (lado // f > limite and lado % (f * 2) == 0 and lado // (f * 2) >= piso
           and (not alinhado or (lado // (f * 2)) % 16 == 0 or (lado // (f * 2) < 16 and piso < 16))):
        f *= 2
    return f


def listar_jars(pasta_mods):
    jars = []
    for raiz, _, arquivos in os.walk(pasta_mods):
        for a in sorted(arquivos):
            if a.lower().endswith((".jar", ".zip")):
                jars.append(os.path.join(raiz, a))
    return sorted(jars, key=lambda p: os.path.basename(p).lower())


def ler_mcmeta(z, nome):
    try:
        bruto = z.read(nome + ".mcmeta")
    except KeyError:
        return None, None
    try:
        return bruto, json.loads(bruto.decode("utf-8-sig"))
    except Exception:
        return bruto, None


def planejar(jars, perfil, log):
    """Retorna lista de tarefas e estatísticas (antes/depois em pixels)."""
    vistos = {}
    duplicados = set()
    tarefas = []
    est = dict(anim_antes=0, anim_depois=0, est_antes=0, est_depois=0, out_antes=0, out_depois=0)
    for jar in jars:
        try:
            z = zipfile.ZipFile(jar)
        except Exception as e:
            log(f"[aviso] não abri {os.path.basename(jar)}: {e}")
            continue
        nomes = set(z.namelist())
        for nome in sorted(nomes):
            m = PADRAO_TEXTURA.match(nome)
            if not m:
                continue
            dominio, caminho = m.group(1).lower(), m.group(2)
            chave = (dominio, caminho.lower())
            if chave in vistos:
                duplicados.add(chave)
                continue
            vistos[chave] = jar
            if dominio in DOMINIOS_IGNORADOS:
                continue
            try:
                with z.open(nome) as fh:
                    cab = fh.read(24)
            except Exception:
                continue
            d = dim_png(cab)
            if not d:
                continue
            w, h = d
            atlas = bool(PADRAO_ATLAS.match(caminho))
            meta_bruto, meta = (None, None)
            if (nome + ".mcmeta") in nomes:
                meta_bruto, meta = ler_mcmeta(z, nome)
            anim = atlas and isinstance(meta, dict) and isinstance(meta.get("animation"), dict)

            if anim:
                a = meta["animation"]
                if "width" in a or "height" in a or w == 0 or h % w:
                    est["anim_antes"] += w * h; est["anim_depois"] += w * h
                    continue
                quadros = h // w
                f = fator_pot2(w, perfil["anim"])
                passo = 1
                if quadros > perfil["frames"] and "frames" not in a:
                    passo = math.ceil(quadros / perfil["frames"])
                novo_w = w // f
                novos_quadros = len(range(0, quadros, passo))
                est["anim_antes"] += w * h
                est["anim_depois"] += novo_w * novo_w * novos_quadros
                if f > 1 or passo > 1:
                    tarefas.append(dict(jar=jar, nome=nome, dominio=dominio, caminho=caminho, tipo="anim",
                                        w=w, h=h, f=f, passo=passo, quadros=quadros, meta=meta,
                                        meta_bruto=meta_bruto))
                continue

            if atlas:
                est["est_antes"] += w * h
                if w != h:  # 1.7.10 recusa sprite não quadrado sem mcmeta; não mexer
                    est["est_depois"] += w * h
                    continue
                f = fator_pot2(w, perfil["estatico"] if w <= 128 else perfil["modelo"])
                est["est_depois"] += (w // f) * (h // f)
                if f > 1:
                    tarefas.append(dict(jar=jar, nome=nome, dominio=dominio, caminho=caminho, tipo="estatico",
                                        w=w, h=h, f=f, meta_bruto=meta_bruto))
                continue

            est["out_antes"] += w * h
            if any(p in "/" + caminho.lower() for p in CAMINHOS_IGNORADOS):
                est["out_depois"] += w * h
                continue
            lado = max(w, h)
            f = 1
            while lado // f > perfil["outros"] and w % (f * 2) == 0 and h % (f * 2) == 0:
                f *= 2
            est["out_depois"] += (w // f) * (h // f)
            if f > 1:
                tarefas.append(dict(jar=jar, nome=nome, dominio=dominio, caminho=caminho, tipo="outro",
                                    w=w, h=h, f=f, meta_bruto=meta_bruto))
        z.close()
    # Se dois jars trazem o mesmo asset, não dá para saber qual vence em tempo de execução: pular.
    tarefas = [t for t in tarefas if (t["dominio"], t["caminho"].lower()) not in duplicados]
    return tarefas, est, len(duplicados)


def executar(tarefas, raiz_mc, log):
    from PIL import Image  # importado só aqui para --simular funcionar sem Pillow

    saida = os.path.join(raiz_mc, PASTA_SAIDA)
    gerados = []
    por_jar = {}
    for t in tarefas:
        por_jar.setdefault(t["jar"], []).append(t)
    for jar, lista in por_jar.items():
        with zipfile.ZipFile(jar) as z:
            for t in lista:
                try:
                    img = Image.open(io.BytesIO(z.read(t["nome"])))
                    img.load()
                except Exception as e:
                    log(f"[aviso] falhou ao ler {t['nome']}: {e}")
                    continue
                if img.mode != "RGBA":
                    img = img.convert("RGBA")
                f = t["f"]
                meta_saida = t.get("meta_bruto")
                if t["tipo"] == "anim":
                    w, q, passo = t["w"], t["quadros"], t["passo"]
                    nw = w // f
                    indices = list(range(0, q, passo))
                    nova = Image.new("RGBA", (nw, nw * len(indices)))
                    for i, idx in enumerate(indices):
                        quadro = img.crop((0, idx * w, w, (idx + 1) * w))
                        if f > 1:
                            quadro = quadro.resize((nw, nw), Image.BOX)
                        nova.paste(quadro, (0, i * nw))
                    img = nova
                    if passo > 1:
                        meta = json.loads(json.dumps(t["meta"]))
                        a = meta["animation"]
                        a["frametime"] = max(1, int(a.get("frametime", 1))) * passo
                        meta_saida = json.dumps(meta, indent=2).encode("utf-8")
                else:
                    img = img.resize((t["w"] // f, t["h"] // f), Image.BOX)

                destino = os.path.join(saida, t["dominio"], *t["caminho"].split("/"))
                os.makedirs(os.path.dirname(destino), exist_ok=True)
                img.save(destino, "PNG", compress_level=9)
                gerados.append(os.path.relpath(destino, raiz_mc))
                # O mcmeta PRECISA acompanhar o png: o resource manager só procura o .mcmeta
                # no pack que forneceu o png ou acima dele.
                if meta_saida is not None:
                    with open(destino + ".mcmeta", "wb") as fh:
                        fh.write(meta_saida)
                    gerados.append(os.path.relpath(destino + ".mcmeta", raiz_mc))
    return gerados


def limpar(raiz_mc, log):
    caminho = os.path.join(raiz_mc, MANIFESTO)
    if not os.path.isfile(caminho):
        log("Nada para limpar (manifesto não encontrado).")
        return
    with open(caminho, encoding="utf-8") as fh:
        man = json.load(fh)
    n = 0
    for rel in man.get("arquivos", []):
        p = os.path.join(raiz_mc, rel)
        if os.path.isfile(p):
            os.remove(p)
            n += 1
    # remove pastas vazias criadas pela ferramenta
    base = os.path.join(raiz_mc, PASTA_SAIDA)
    for raiz, dirs, arqs in os.walk(base, topdown=False):
        if raiz != base and not os.listdir(raiz):
            os.rmdir(raiz)
    os.remove(caminho)
    log(f"Removidos {n} arquivos gerados anteriormente.")


def mib(px, mip=False):
    return px * 4 * (4 / 3 if mip else 1) / 2 ** 20


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("minecraft", help="pasta .minecraft do pack (a que contém mods/ e config/)")
    ap.add_argument("--perfil", choices=sorted(PERFIS), default="leve")
    ap.add_argument("--simular", action="store_true", help="só calcula a economia, não grava nada")
    ap.add_argument("--limpar", action="store_true", help="remove os arquivos gerados antes")
    ap.add_argument("--anim", type=int, help="sobrescreve a largura máx. de frame animado")
    ap.add_argument("--frames", type=int, help="sobrescreve o nº máx. de frames por animação")
    ap.add_argument("--estatico", type=int, help="sobrescreve o lado máx. de sprite estático")
    ap.add_argument("--modelo", type=int, help="sobrescreve o lado máx. de texturas de modelo em blocks/items")
    ap.add_argument("--outros", type=int, help="sobrescreve o lado máx. das demais texturas")
    ap.add_argument("-v", "--verboso", action="store_true")
    args = ap.parse_args()

    raiz = os.path.abspath(args.minecraft)
    mods = os.path.join(raiz, "mods")
    if not os.path.isdir(mods):
        sys.exit(f"Pasta de mods não encontrada: {mods}")
    log = print
    if args.limpar:
        limpar(raiz, log)
        return

    perfil = dict(PERFIS[args.perfil])
    for k in ("anim", "frames", "estatico", "modelo", "outros"):
        if getattr(args, k):
            perfil[k] = getattr(args, k)

    tarefas, est, dup = planejar(listar_jars(mods), perfil, log)
    print(f"Perfil: {args.perfil} {perfil}")
    print(f"Texturas a reduzir: {len(tarefas)}  (assets duplicados entre jars ignorados: {dup})")
    print("                              antes        depois")
    print(f"  Animadas (heap residente) : {mib(est['anim_antes'], True):7.0f} MiB -> {mib(est['anim_depois'], True):7.0f} MiB  (com mipmap 4)")
    print(f"  Atlas estático (pico/VRAM): {mib(est['est_antes'], True):7.0f} MiB -> {mib(est['est_depois'], True):7.0f} MiB")
    print(f"  Outras (entidades etc.)   : {mib(est['out_antes']):7.0f} MiB -> {mib(est['out_depois']):7.0f} MiB  (se todas carregadas)")
    if args.verboso:
        for t in sorted(tarefas, key=lambda t: -t["w"] * t["h"])[:60]:
            extra = f" frames {t['quadros']}->{len(range(0, t['quadros'], t['passo']))}" if t["tipo"] == "anim" else ""
            print(f"   {t['tipo']:8s} 1/{t['f']}{extra}  {t['w']}x{t['h']}  {t['dominio']}:{t['caminho']}")
    if args.simular:
        return

    limpar(raiz, lambda *_: None)
    gerados = executar(tarefas, raiz, log)
    with open(os.path.join(raiz, MANIFESTO), "w", encoding="utf-8") as fh:
        json.dump(dict(perfil=args.perfil, limites=perfil, arquivos=gerados), fh, indent=1)
    print(f"Gerados {len(gerados)} arquivos em {os.path.join(raiz, PASTA_SAIDA)}")


if __name__ == "__main__":
    main()
