import { createApp, defineComponent, h, type VNode } from 'vue'
import MahjongTileFace from './components/MahjongTileFace.vue'
import TileView from './yaoming/TileView.vue'
import MeldView from './yaoming/MeldView.vue'
import HandTile from './yaoming/HandTile.vue'
import type { Meld, Tile } from './yaoming/types'
import './yaoming/yaoming.css'
import './yaoming/playability.css'

// A Vite development-only entry. It is not imported by main.ts or the production index.html.
// These are isolated artwork specimens, not room snapshots, identities, or game actions.
const numerals = ['', '一', '二', '三', '四', '五', '六', '七', '八', '九']
const honors = ['', '东风', '南风', '西风', '北风', '红中', '发财', '白板']
function tile(suit: string, rank: number, suffix = '', red = false): Tile {
  const label = suit === 'HONORS' ? honors[rank] : `${numerals[rank]}${suit === 'DOTS' ? '筒' : suit === 'BAMBOO' ? '条' : '万'}`
  return { id: `preview-${suit}-${rank}-${suffix}`, suit, rank, label: red ? `红宝牌 ${label}` : label, red }
}
const suitRows = [
  { id: 'dots', name: '筒子', suit: 'DOTS', count: 9 },
  { id: 'bamboo', name: '条子', suit: 'BAMBOO', count: 9 },
  { id: 'characters', name: '万子', suit: 'CHARACTERS', count: 9 },
  { id: 'honors', name: '风牌与箭牌', suit: 'HONORS', count: 7 },
]
const sevenDots = tile('DOTS', 7), sevenBamboo = tile('BAMBOO', 7)
function sameTiles(suit: string, rank: number, count: number, group: string) {
  return Array.from({ length: count }, (_, index) => tile(suit, rank, `${group}-${index}`))
}
function exposed(type: Meld['type'], tiles: Tile[], fromSeat: number, added = false): Meld {
  return { type, tiles, fromSeat, claimedTileId: tiles[1].id, concealed: false, added }
}
const kongTiles = sameTiles('BAMBOO', 7, 4, 'concealed')
const meldSamples: { name: string; meld: Meld; reveal?: boolean }[] = [
  { name: '吃 · 上家横置供牌', meld: exposed('CHI', [tile('DOTS', 6, 'chi'), tile('DOTS', 7, 'chi'), tile('DOTS', 8, 'chi')], 2) },
  { name: '碰 · 上家供牌', meld: exposed('PONG', sameTiles('DOTS', 7, 3, 'pong-left'), 2) },
  { name: '碰 · 下家供牌', meld: exposed('PONG', sameTiles('BAMBOO', 7, 3, 'pong-right'), 1) },
  { name: '明杠 · 四张含横置', meld: exposed('KONG', sameTiles('DOTS', 7, 4, 'open-kong'), 1) },
  { name: '加杠 · 供牌上方叠放', meld: exposed('KONG', sameTiles('BAMBOO', 7, 4, 'added-kong'), 1, true) },
  { name: '暗杠 · 对手视角两张牌背', meld: { type: 'KONG', tiles: kongTiles, fromSeat: 0, claimedTileId: '', concealed: true } },
  { name: '暗杠 · 结算完整展示', meld: { type: 'KONG', tiles: kongTiles, fromSeat: 0, claimedTileId: '', concealed: true }, reveal: true },
]
const sections = [
  ['honor-glyphs', '上游字牌 · 大小尺寸'],
  ['catalog', '34 张完整目录'], ['sevens', '七筒 / 七条尺寸'], ['red-fives', '普通五 / 红五'],
  ['melds', '横置与叠牌'], ['states', '选中与暗牌'],
]
function figure(face: Tile, className = '', label = face.label): VNode {
  return h('figure', { class: className, 'aria-label': `${label}视觉样例` }, [h(TileView, { tile: face }), h('figcaption', label)])
}
function section(id: string, title: string, note: string, children: VNode[]): VNode {
  return h('section', { id, class: 'preview-section', 'aria-labelledby': `preview-title-${id}` }, [
    h('h2', { id: `preview-title-${id}` }, title), h('p', note), ...children,
  ])
}

const PreviewApp = defineComponent({
  name: 'TileArtVisualPreview',
  setup: () => () => h('div', { class: 'ym-app' }, h('main', { class: 'preview-sheet' }, [
    h('h1', 'mahjong_graphic 上游 SVG · 牌面验收'),
    h('p', { class: 'preview-intro' }, '使用 mahjong_graphic 固定提交 3e275804ff58325306710bef3a7406860444bc6a 的完整 SVG 原图案：34 种普通牌与 0m、0p、0s 原生红五。本地矢量素材保留上游路径、颜色与比例，不添加数字或字母角标，运行时不访问外部素材网站。所有说明均在牌外，不连接牌局、不含玩家身份、不触发游戏动作。'),
    h('nav', { class: 'preview-nav', 'aria-label': '视觉验收章节' }, sections.map(([id, name]) => h('a', { href: `#${id}` }, name))),
    section('honor-glyphs', '上游字牌 · 大小尺寸', '东、南、西、北、中、發、白板均使用上游完整 SVG 原图案。游戏中的中、發、白板分别对应上游 7z、6z、5z。使用正式 TileView 检查比例与小牌辨识度；名称和尺寸说明均在牌外，绿桌面底仅用于验收。', [
      ...[
        { id: 'large', label: '大牌 · 91 × 134 px', cell: '110px' },
        { id: 'medium', label: '正常手牌 · 43 × 61 px', cell: '54px' },
        { id: 'small', label: '小牌 · 24 × 34 px', cell: '36px' },
      ].map(size => h('article', {
        class: ['preview-honor-row', `preview-size-${size.id}`],
        'aria-label': `${size.label}字牌对照`, style: { '--preview-honor-cell': size.cell },
      }, [
        h('h3', size.label),
        h('div', { class: 'preview-honor-grid' }, ['東', '南', '西', '北', '中', '發', '白板'].map((label, index) =>
          figure(tile('HONORS', index + 1, `honor-size-${size.id}`), 'preview-honor-figure', label))),
      ])),
    ]),
    section('catalog', '34 张完整目录', '逐张核对固定上游 SVG 的原图案、配色、浅色牌底与比例。完整矢量牌面等比显示，不补画、不着色、不添加数字或字母角标；牌名仅在牌外说明。', suitRows.map(row =>
      h('div', { 'aria-label': `${row.name}完整目录` }, [h('h3', row.name), h('div', { class: ['preview-grid', { 'preview-honors': row.suit === 'HONORS' }] },
        Array.from({ length: row.count }, (_, index) => figure(tile(row.suit, index + 1), 'preview-card preview-catalog-tile')))]))),
    section('sevens', '七筒 / 七条（索）· 三尺寸对照', '大尺寸检查上游 SVG 图案细节；中尺寸对应手牌，小尺寸对应牌河和副露。三种尺寸均使用同一份完整矢量原图案，等比缩放，不拉伸或补画细节。', [
      h('div', { class: 'preview-sizes' }, [
        { id: 'large', label: '大 · 91 × 134 px' }, { id: 'medium', label: '中 · 43 × 61 px' }, { id: 'small', label: '小 · 24 × 34 px' },
      ].map(size => h('article', { class: ['preview-size-group', `preview-size-${size.id}`], 'aria-label': `${size.label}七筒七条` }, [
        h('h3', size.label), h('div', { class: 'preview-size-pair' }, [figure(sevenDots), figure(sevenBamboo)]),
      ]))),
    ]),
    section('red-fives', '普通五 / 上游原生红五', '右侧红宝牌直接采用上游 0m、0s、0p 原生 SVG，与普通五分别使用独立原图案；不使用滤镜染色、不添加“赤”徽章或其他角标。红宝牌名称保留在牌外及无障碍说明中。', [
      h('div', { class: 'preview-red-grid' }, ['CHARACTERS', 'BAMBOO', 'DOTS'].map(suit => h('div', { class: 'preview-five-pair' }, [
        figure(tile(suit, 5, 'normal'), '', `普通${tile(suit, 5).label}`), figure(tile(suit, 5, 'red', true)),
      ]))),
    ]),
    section('melds', '真实副露组件 · 横置 / 加杠 / 暗杠', '直接使用 MeldView 显示固定上游 SVG 原图案，保留牌源标记、旋转、叠牌与暗杠显示逻辑，不另外制作副露图片。', [
      h('div', { class: 'preview-meld-grid' }, meldSamples.map(sample => h('article', { class: 'preview-meld-card', 'aria-label': sample.name }, [
        h('h3', sample.name), h('div', { class: 'preview-meld-area' }, h(MeldView, { meld: sample.meld, ownerSeat: 0, reveal: !!sample.reveal })),
      ]))),
    ]),
    section('states', '牌面状态与隔离检查', '选中样例采用正式手牌组件且禁用交互。传入牌值的暗牌样例仍必须只有背面，不能出现图案或牌名。', [
      h('div', { class: 'preview-state-grid' }, [
        h('article', { class: 'preview-state-card', 'aria-label': '默认手牌状态' }, [h('div', h(HandTile, { tile: sevenDots, selected: false, disabled: true })), h('p', '默认手牌')]),
        h('article', { class: 'preview-state-card', 'aria-label': '选中手牌状态' }, [h('div', h(HandTile, { tile: sevenBamboo, selected: true, disabled: true })), h('p', '选中手牌 · 高亮与抬起')]),
        h('article', { class: 'preview-state-card', 'aria-label': '摸牌独立标记' }, [h('div', h(HandTile, { tile: sevenDots, selected: false, disabled: true, drawn: true })), h('p', '摸牌位置标记（牌外）')]),
        h('article', { class: 'preview-state-card', 'aria-label': '暗牌隐私样例' }, [h('div', [h(TileView, { tile: tile('HONORS', 5, 'hidden'), back: true }), h(TileView, { tile: tile('DOTS', 5, 'hidden-small', true), back: true, small: true })]), h('p', '暗牌 · 正常 / 小尺寸')]),
        h('article', { class: 'preview-state-card', 'aria-label': '共享上游 SVG 面层隔离样例' }, [
          h('div', { class: 'preview-face-isolation' }, [sevenDots, sevenBamboo].map(face => h('span', { class: 'preview-face-only' }, h(MahjongTileFace, { suit: face.suit, rank: face.rank, label: face.label })))),
          h('p', '共享上游 SVG · 无 TileView 外框'),
        ]),
      ]),
    ]),
  ])),
})

createApp(PreviewApp).mount('#tile-art-preview')
