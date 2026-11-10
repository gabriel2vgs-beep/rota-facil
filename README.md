# Rota Fácil

Aplicativo Android (Kotlin, Jetpack Compose, Room, CameraX, ML Kit, OSM/OSRM) para importar o romaneio Shopee,
agrupar pacotes por parada, organizar a rota, bipar e marcar entregas — tudo salvo no celular e funcionando offline.

## Estado real do projeto (leia primeiro)

| Parte | Situação |
|---|---|
| `core/` — importação CSV/XLSX, mapeamento de colunas, agrupamento por Stop, validações, anti-duplicidade, bipagem, regras de entrega, otimização de rota, cliente OSRM | **Compilado e testado** (68 testes JUnit, incluindo os 200 pacotes/171 paradas da planilha real e as regras da foto de prova) |
| `app/` — telas, Room, câmera + ML Kit, GPS, mapa, leitor .xls, foto de prova | **Escrito, mas NÃO compilado e NÃO testado em aparelho** (não havia Android SDK nem acesso ao Maven onde foi desenvolvido) |
| APK | **Não gerado** — gere com os passos abaixo |

Espere pequenos erros de compilação na primeira vez que abrir o `app/` no Android Studio (imports, versões). São correções simples.

## Como gerar o APK

**Opção A — Android Studio (mais simples)**
1. Instale o Android Studio (gratuito) e abra esta pasta (`rota-facil`).
2. Aguarde o "Gradle sync". Se pedir para instalar o Android SDK 35, aceite.
3. Menu **Build ▸ Build Bundle(s) / APK(s) ▸ Build APK(s)**.
4. O arquivo fica em `app/build/outputs/apk/debug/app-debug.apk`.

**Opção B — GitHub Actions (sem instalar nada)**
1. Crie um repositório no GitHub e envie esta pasta.
2. Aba **Actions ▸ Gerar APK ▸ Run workflow**.
3. Baixe `RotaFacil-APK` nos artefatos da execução. O fluxo também roda os testes da lógica antes.

## Como instalar no celular
1. Copie `app-debug.apk` para o celular.
2. Abra o arquivo; se o Android pedir, permita "instalar apps desconhecidos" para o app que você usou para abrir.
3. Toque em Instalar. Ao abrir, permita **Localização** e **Câmera**.

## Como usar
1. **Início ▸ IMPORTAR ROMANEIO** e escolha o `.xlsx`/`.csv`. Confira a prévia (pacotes, paradas, avisos, colunas reconhecidas) e toque em **CONFIRMAR**.
2. **ORGANIZAR ROTA** (precisa de internet e de GPS ou ponto inicial manual em *Ajustes*).
3. **BIPAR PACOTE**: aponte a câmera para o código. Aparece a parada, o endereço e os pacotes dela.
4. Na parada, marque **ENTREGUE** / **NÃO ENTREGUE** em cada pacote ou na parada toda.
5. Durante o dia, **Mapa ▸ RECALCULAR A PARTIR DE AQUI** reorganiza só as paradas ainda abertas.

## Foto de prova de entrega
- Em cada parada há **TIRAR FOTO DA PARADA** (vale para todos os pacotes dela, ex.: foto da porta ou do local) e um botão de câmera em **cada pacote** (vale só para aquele pacote). Dá para tirar mais de uma foto.
- Em **Ajustes ▸ Prova de entrega**, ligue **Exigir foto ao marcar ENTREGUE**. Com isso, ENTREGUE (ou TODOS ENTREGUES) abre a câmera primeiro; a entrega só é registrada **depois** que a foto foi salva. Se salvar a foto falhar, nada é marcado como entregue.
- Depois de tirar a foto você a revisa e escolhe **USAR FOTO** ou **REFAZER**. Foto não confirmada é descartada.
- Cada foto guarda data/hora, localização do GPS (se disponível) e uma impressão digital SHA-256 do arquivo. Toque na miniatura para ver; lá também dá para apagar (com confirmação).
- Funciona **offline**: as fotos ficam na memória privada do app (reduzidas a no máx. 1600 px, ~300 KB cada) e não aparecem na galeria. Apagar o romaneio apaga as fotos dele.
- Não implementado: enviar/exportar as fotos para fora do aparelho (a desinstalação do app apaga as fotos).

## Rotas: o que você precisa saber
- O app usa o **OSRM** (malha viária do OpenStreetMap, respeita mão única). O servidor padrão (`router.project-osrm.org`) é **público e de demonstração**, sem garantia de disponibilidade e sem trânsito em tempo real.
- Para uso diário, ponha seu próprio servidor OSRM e informe o endereço em *Ajustes ▸ Serviço de rotas*.
- **Trânsito em tempo real não está implementado.** Exigiria Google Routes API (chave paga). O código está isolado em `MatrixProvider`, então trocar é local.
- Sem internet: a última rota e todas as entregas continuam disponíveis; só recalcular a rota e carregar novos blocos do mapa precisam de rede.
- Navegação externa: Google Maps ou Waze, escolha em *Ajustes*.

## Testes
- Lógica (roda em qualquer computador com Java 17): `gradle :core:test`
  (ou, sem rede, `tools/build-core.sh test`)
- Persistência no aparelho: `gradle :app:connectedAndroidTest` (emulador ou celular conectado)

## Decisões e limites conhecidos
- Códigos SPX nunca são alterados; a busca na bipagem é por igualdade exata (sem aproximação).
- Código repetido na planilha **bloqueia** a importação (ou, se você permitir, a bipagem pede para escolher a parada).
- A importação é tudo-ou-nada: se a conferência final falhar, nada é salvo.
- Importar o mesmo romaneio duas vezes é bloqueado.
- Leitura de `.xls` antigo usa a biblioteca JXL e não foi testada; se falhar, salve como `.xlsx`.
- O leitor só aceita a leitura quando a câmera vê **um** código por vez.
- Um romaneio por vez é exibido (o mais recente).
