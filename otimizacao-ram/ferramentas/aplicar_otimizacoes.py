#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Aplica o pacote de otimização de RAM do Technic Forbidden em uma pasta .minecraft.

    python3 aplicar_otimizacoes.py CAMINHO/.minecraft                      # perfil leve
    python3 aplicar_otimizacoes.py CAMINHO/.minecraft --perfil batata
    python3 aplicar_otimizacoes.py CAMINHO/.minecraft --simular            # só mostra o que faria

Passos:
  1. Configs (com backup *.bak-otimizacao na primeira alteração):
     - hodgepodge.cfg : internResourceLocationDomain / internUniqueIdentifierModid = true
     - coretweaks.cfg : volta o cache de transformers aos valores do pack (sem diffs, 256 MB). Diffs + teto
                        menor deixaram o boot do jogador ~70 s mais lento (437 s x 367 s), ver BOOT.md
     - options.txt    : mipmapLevels (leve/batata = 0)
     - journeymap     : desliga o mapa topográfico (leve/batata)
  2. Texturas: chama otimizar_texturas.py com o mesmo perfil.

O Mirai (Distant Horizons da Rede Akashic) não é tocado: é configurado no próprio projeto.
"""
import argparse
import os
import re
import shutil
import sys

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AQUI)
import otimizar_texturas  # noqa: E402

# (arquivo relativo à .minecraft, regex com grupo 1 = prefixo e grupo 2 = valor atual, valor novo, perfis)
TODOS = ("qualidade", "equilibrado", "leve", "batata")
LEVES = ("leve", "batata")
AJUSTES = [
    ("config/hodgepodge.cfg", r"(B:internResourceLocationDomain=)(\w+)", "true", TODOS),
    ("config/hodgepodge.cfg", r"(B:internUniqueIdentifierModid=)(\w+)", "true", TODOS),
    # valores originais do pack: diffs custam ~25% por classe vinda do cache e o teto de 128 MB descartava
    # ~21 mil entradas; o pico de RAM que isso evitava é pequeno perto do tempo perdido em todo boot
    ("config/coretweaks.cfg", r"(B:useDiffsInTransformerCache=)(\w+)", "false", TODOS),
    ("config/coretweaks.cfg", r"(I:liteTransformerCacheMaxSizeMB=)(-?\d+)", "256", TODOS),
    ("options.txt", r"(?m)^(mipmapLevels:)(\d+)", "0", LEVES),
    ("journeymap/config/6.0/journeymap.core.config", r'("mapTopography":\s*")(\w+)"', "false", LEVES),
]

def aplicar_ajustes(raiz, perfil, simular):
    por_arquivo = {}
    for rel, padrao, valor, perfis in AJUSTES:
        if perfil in perfis:
            por_arquivo.setdefault(rel, []).append((padrao, valor))
    for rel, regras in por_arquivo.items():
        caminho = os.path.join(raiz, *rel.split("/"))
        if not os.path.isfile(caminho):
            print(f"  [pulado] {rel} não existe")
            continue
        with open(caminho, encoding="utf-8", errors="surrogateescape", newline="") as fh:
            original = fh.read()
        novo = original
        for padrao, valor in regras:
            m = re.search(padrao, novo)
            if not m:
                estado = "chave ausente"
            elif m.group(2) == valor:
                estado = f"já estava {valor}"
            else:
                estado = f"{m.group(2)} -> {valor}"
                novo = re.sub(padrao, lambda mm: mm.group(0).replace(mm.group(1) + mm.group(2), mm.group(1) + valor, 1), novo)
            print(f"  {rel}: {re.search(r'[A-Za-z]{4,}', padrao).group(0)} -> {estado}")
        if novo != original and not simular:
            bak = caminho + ".bak-otimizacao"
            if not os.path.exists(bak):
                shutil.copy2(caminho, bak)
            with open(caminho, "w", encoding="utf-8", errors="surrogateescape", newline="") as fh:
                fh.write(novo)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("minecraft")
    ap.add_argument("--perfil", choices=sorted(otimizar_texturas.PERFIS), default="leve")
    ap.add_argument("--sem-texturas", action="store_true", help="não gera as texturas reduzidas")
    ap.add_argument("--simular", action="store_true")
    args = ap.parse_args()

    raiz = os.path.abspath(args.minecraft)
    if not os.path.isdir(os.path.join(raiz, "mods")):
        sys.exit(f"Não parece uma .minecraft (sem pasta mods): {raiz}")

    print(f"== Configs (perfil {args.perfil})")
    aplicar_ajustes(raiz, args.perfil, args.simular)

    if not args.sem_texturas:
        print("== Texturas")
        argv = [raiz, "--perfil", args.perfil] + (["--simular"] if args.simular else [])
        sys.argv = ["otimizar_texturas.py"] + argv
        otimizar_texturas.main()


if __name__ == "__main__":
    main()
