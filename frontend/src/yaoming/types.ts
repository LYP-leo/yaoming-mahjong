export interface Tile { id: string; suit: string; rank: number; label: string; red?: boolean }
export type RuleId = 'yaoming-3p' | 'yaoming-4p'
/** Missing metadata belongs to historical three-player rooms, never the current occupancy. */
export interface RuleIdentity { ruleId?: RuleId; ruleName?: string; capacity?: number }
export type DiscardKind = 'TSUMOGIRI' | 'TEDASHI'
export interface Meld { type: 'CHI' | 'PONG' | 'KONG'; tiles: Tile[]; fromSeat: number; claimedTileId: string; concealed: boolean; added?: boolean }
export interface Player {
  id: string; name: string; seat: number; wind: string; score: number; bot: boolean; ready: boolean;
  online: boolean; acknowledged: boolean; trustee: boolean; hand: Tile[]; handSize: number;
  discards: Tile[]; melds: Meld[]; discardedCodes: string[]; passedCodes: string[];
  discardKinds?: Record<string, DiscardKind> | null;
  drawnTileId?: string | null; trusteeReason?: 'MANUAL' | 'OFFLINE' | 'TIMEOUT' | null;
}
export interface Action { type: string; label: string; tileIds: string[] }
export interface Fan { id: string; name: string; fan: number; description: string }
export interface Result {
  draw: boolean; matchOver: boolean; title: string; winnerId: string | null; winningTile: Tile | null;
  rawFan: number; fan: number; items: Fan[];
  payments: { fromId: string; toId: string; amount: number; requested: number }[];
  scores: { playerId: string; name: string; score: number; delta: number; rank: number }[];
  hands: { playerId: string; hand: Tile[]; melds: Meld[] }[]; reason: string;
}
export interface RoomView extends RuleIdentity {
  id: string; name: string; version: number; status: string; round: number; roundLabel: string;
  dealerSeat: number; currentSeat: number; wallCount: number; message: string; meId: string;
  players: Player[]; actions: Action[]; result: Result | null; events: { sequence: number; text: string }[];
  dice: { opening: number[]; breaking: number[]; openingSeat: number; breakStack: number } | null;
  deadlineAt: string | number | null; winHint: string;
  serverTime?: string; deadlineKind?: 'DRAW' | 'DISCARD' | 'REACTION' | 'SETTLEMENT' | null;
  lastDiscard?: { tile: Tile; fromSeat: number; claimed: boolean; kind?: DiscardKind | null } | null;
  /** Client-only receipt time. Always overwritten by the store; never sent to the API. */
  clientReceivedAt?: number;
}
export interface RoomSummary extends RuleIdentity { id: string; name: string; status: string; players: number; capacity: number }
export interface Rules { id?: RuleId; playerCount?: number; tileCount?: number; totalRounds?: number; name: string; version: string; description: string; notes: string[]; fans: Fan[]; tiles: Tile[] }
export interface Identity { roomId: string; playerId: string; token: string }
export interface WaitHint { tile: Tile; unseenCount: number; canTsumo: boolean; tsumoFan: number; canRon: boolean; ronFan: number; ronReason: string }
export interface HintResponse { roomId: string; playerId: string; version: number; analysis: { mode: 'WAIT' | 'DISCARD' | 'UNAVAILABLE'; waits: WaitHint[]; discards: { tile: Tile; waits: WaitHint[] }[]; note: string } }
export interface ReplayIdentity extends Identity { roomName: string; playerName: string; savedAt: number }
export interface ReplaySummary extends RuleIdentity { round: number; roundLabel: string; startedAt: number; completedAt: number; frameCount: number; incomplete: boolean; title: string }
export interface ReplayList extends RuleIdentity { roomId: string; roomName: string; hands: ReplaySummary[]; note: string }
export interface ReplayPlayer { id: string; name: string; seat: number; score: number; hand: Tile[]; discards: Tile[]; melds: Meld[]; drawnTileId?: string | null; discardKinds?: Record<string, DiscardKind> | null }
export interface ReplayFrame { index: number; timestamp: number; type: string; actorSeat: number; message: string; status: string; currentSeat: number; wallCount: number; dealerSeat: number; players: ReplayPlayer[]; lastDiscard: RoomView['lastDiscard']; dice: RoomView['dice']; result: Result | null }
export interface HandRecord extends RuleIdentity { roomId: string; roomName: string; round: number; roundLabel: string; startedAt: number; completedAt: number; complete: boolean; incomplete: boolean; frames: ReplayFrame[]; result: Result | null }
