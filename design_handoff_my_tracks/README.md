# Handoff: My Tracks — Tela de sessão de rastreamento

## Overview
Tela principal do app "My Tracks": mostra as métricas de uma sessão de rastreamento (distância, velocidade, tempo) e o mapa do percurso, com ações para exportar, iniciar nova sessão e ver histórico. Redesenho de uma tela existente, aplicando UX/UI moderno e minimalista sobre o design system "Organic" (warm, arredondado, terracota + sage).

## About the Design Files
Os arquivos deste pacote são **referências de design em HTML** — protótipos mostrando aparência e comportamento pretendidos, não código de produção para copiar diretamente. A tarefa é **recriar este design HTML no ambiente já existente do app** (Android nativo/Kotlin, Flutter, React Native, etc. — o que já for usado no projeto real de "My Tracks") usando os padrões e bibliotecas já estabelecidos ali. Se não houver ambiente definido, escolher o framework mais apropriado para um app mobile e implementar lá.

## Fidelity
**Alta fidelidade (hifi)**: cores, tipografia, espaçamento e componentes finais, prontos para reproduzir pixel a pixel. Único ponto ilustrativo: o mapa é um desenho SVG placeholder — no app real, usar o componente de mapa real (ex. Google Maps SDK) com os mesmos estilos de marcadores/cartão arredondado descritos abaixo.

## Screens / Views

### Tela: Sessão (My Tracks)
**Propósito:** o usuário revisa as métricas da sessão de rastreamento recém-concluída (ou em andamento) e decide exportar, ver histórico ou iniciar uma nova sessão.

**Layout geral:** tela mobile (referência 390×844px), fundo `--color-bg` (#f5ead8), estrutura em coluna: status bar → header → conteúdo rolável → footer fixo com ações.

1. **Status bar** (44px altura): hora à esquerda, indicador de bateria à direita, texto 14px/600.

2. **Header** (padding 8px 20px 0): botão de menu (ícone hambúrguer, `btn-ghost btn-icon`, 44×44px, pill) à esquerda; botão "Exportar" (`btn-secondary`, pill, 44px altura, ícone de download + label) à direita. Justificado com `space-between`.

3. **Conteúdo** (scroll vertical, padding 16px 24px 0, gap 20px entre seções):
   - **Contexto + título:** texto pequeno "Hoje · Praia do Gravatá" (14px, cor `--color-neutral-700`) acima do título "My Tracks" (fonte de display `--font-heading`, 24px, peso 400).
   - **Linha Distância + Velocidade instantânea** (`display:flex; align-items:flex-end; gap:12px`, dois blocos `flex:1`):
     - **Distância total:** label 14px/600; valor "3,99" em `--font-heading`, 48px, cor `--color-accent-700`, unidade "km" 18px/600 ao lado (baseline).
     - **Velocidade instantânea** (só aparece durante coleta ativa — ver State Management): cartão `background: var(--color-accent-2-100)`, `border-radius: var(--radius-lg)`, padding 14px 16px. Linha com bolinha 8px (`--color-accent-2-700`) + label 12px/600 "Velocidade instantânea"; valor "5,20" 24px/700 + unidade "km/h" 13px/600, cor `--color-accent-2-900`.
   - **Cartões secundários (grid 2 colunas, gap 12px):** dois cartões `background: var(--color-surface)`, `border-radius: var(--radius-lg)`, padding 16px 18px — um para "Velocidade média" (4,86 km/h) e outro para "Tempo total" (49:16). Label 13px/600 em cinza, valor 24px/700.
   - **Barra de tempo (movimento vs. parado):** barra horizontal 14px de altura, pill, dividida em dois segmentos com `gap: 4px` — segmento verde-sage (`--color-accent-2`) proporcional ao tempo em movimento (75.7% neste exemplo) e segmento neutro (`--color-neutral-300`) para o tempo parado. Abaixo, legenda com dois indicadores (bolinha 10px + texto 14px): "Em movimento 37:17" e "Parado 11:58".
   - **Cartão de mapa:** container `border-radius: var(--radius-lg)`, `overflow: hidden`, altura 220px (expande para 420px ao tocar o botão de expandir — ver Interações). Contém o percurso desenhado sobre o mapa, dois marcadores (início em sage escuro, fim em terracota escuro), duas tags flutuantes no canto superior esquerdo ("Início" tag-accent-2, "Fim" tag-accent, fundo `--color-bg`) e um botão de ícone circular no canto superior direito (expandir/recolher, fundo `--color-bg`, `--shadow-md`).

4. **Footer** (fixo, padding 16px 24px 28px, fundo `--color-bg`, `display:flex; gap:12px`):
   - Botão "Histórico" — `btn-secondary`, `flex:1`, 56px altura, pill, ícone de relógio/histórico + label.
   - Botão "Nova sessão" — `btn-primary`, `flex:1.4` (mais largo, ação principal), 56px altura, pill, ícone de play + label.

## Interactions & Behavior
- **Expandir mapa:** toque no botão de ícone no cartão do mapa alterna a altura do cartão entre 220px e 420px (sem animação definida no protótipo — recomenda-se transição suave de altura, ~250ms ease, ao implementar).
- **Exportar / Histórico / Nova sessão:** ações de navegação/exportação — comportamento funcional (gerar arquivo, navegar para tela de histórico, iniciar nova sessão) deve seguir a lógica já existente no app; este pacote cobre apenas a UI.
- Todos os botões e áreas de toque têm altura mínima de 44px (padrão de acessibilidade mobile).
- Estados de hover/pressed/focus devem seguir os já definidos no design system Organic (ramps de cor, anel de foco 2px `--color-accent`) — não restilizar por tela.

## State Management
- `mapExpanded` (boolean): controla a altura do cartão de mapa (220px / 420px).
- `isCollecting` (boolean): quando `true` (coleta de dados ativa), exibe o cartão de "Velocidade instantânea" ao lado da distância total; quando `false`, esse espaço fica só com a distância.
- Dados de sessão exibidos (distância, velocidade média/instantânea, tempo total, tempo em movimento, tempo parado, coordenadas do percurso, marcadores) vêm da sessão de rastreamento ativa/selecionada — fonte de dados já existente no app.

## Design Tokens
Definidos em `organic-styles.css` (anexo). Principais usados nesta tela:
- Cores: `--color-bg` #f5ead8, `--color-surface` #ebddc5, `--color-text` #201e1d, `--color-accent` #c67139 (+ ramps 100–900), `--color-accent-2` #7a8a5e (+ ramps 100–900), `--color-neutral-100…900`.
- Tipografia: `--font-heading` "Caprasimo" (títulos e número grande de destaque), `--font-body` "Figtree" (todo o resto).
- Espaçamento: `--space-1` a `--space-8` (4.4px–35.2px, escala 1.10×).
- Raio: `--radius-sm` 8px, `--radius-md` 16px, `--radius-lg` 28px; botões/pills usam `border-radius: 999px`.
- Sombra: `--shadow-sm/md/lg` (usadas no botão de expandir mapa e no cartão do telefone).

## Assets
- Ícones: Lucide (https://lucide.dev), stroke-width 2.75, embutidos como SVG inline no HTML de referência (menu, download/exportar, expandir, relógio, play).
- Mapa: SVG placeholder ilustrativo (rota, marcadores, água) — substituir por integração de mapa real no app.
- Fontes: Caprasimo e Figtree (Google Fonts, carregadas pelo bundle do design system).

## Files
- `my-tracks-design.html` — protótipo HTML completo da tela (design de referência).
- `organic-styles.css` — folha de tokens e componentes do design system Organic usada pelo protótipo.
