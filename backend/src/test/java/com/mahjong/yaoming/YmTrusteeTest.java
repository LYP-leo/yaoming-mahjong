package com.mahjong.yaoming;

import com.mahjong.domain.Tile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.mahjong.yaoming.YmViews.Action;
import static org.junit.jupiter.api.Assertions.*;

class YmTrusteeTest {
    private Action discard(Tile tile) { return new Action("DISCARD", "出牌", List.of(tile.id())); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void discardsTheDrawnPhysicalCopyEvenWhenSameCodeOrActionOrderDiffers(boolean reversed) {
        Tile first = YmTiles.of("D5"), drawn = YmTiles.of("D5"), rightmost = YmTiles.of("H7");
        List<Tile> hand = List.of(first, drawn, rightmost);
        List<Action> legal = new ArrayList<>(List.of(discard(first), discard(drawn), discard(rightmost), Action.of("WIN", "自摸")));
        if (reversed) Collections.reverse(legal);
        List<Action> prior = List.copyOf(legal);
        Action result = YmTrustee.choose(hand, drawn.id(), false, legal);
        assertEquals(List.of(drawn.id()), result.tileIds()); assertEquals("DISCARD", result.type());
        assertEquals(prior, legal); assertEquals(List.of(first, drawn, rightmost), hand);
    }

    @ParameterizedTest @ValueSource(strings = {"WIN", "CHI", "PONG", "OPEN_KONG", "CONCEALED_KONG", "ADDED_KONG"})
    void neverChoosesAClaimKongOrWinEvenIfItIsTheOnlyAvailableAction(String ignored) {
        Tile tile = YmTiles.of("B1"); Action forbidden = new Action(ignored, ignored, List.of(tile.id()));
        assertNull(YmTrustee.choose(List.of(tile), tile.id(), false, List.of(forbidden)));
        assertEquals(discard(tile), YmTrustee.choose(List.of(tile), tile.id(), false, List.of(forbidden, discard(tile))));
    }

    @Test void responseAlwaysPassesDespiteRonAndEveryClaimOption() {
        Tile tile = YmTiles.of("B1"); Action pass = Action.of("PASS", "过");
        List<Action> legal = new ArrayList<>();
        for (String type : List.of("WIN", "CHI", "PONG", "OPEN_KONG")) legal.add(new Action(type, type, List.of(tile.id())));
        legal.add(pass); assertSame(pass, YmTrustee.choose(List.of(tile), null, false, legal));
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "null", "present-but-illegal"})
    void missingOrIllegalDrawIdUsesRightmostSortedLegalTileWithoutMutation(String mode) {
        Tile white = YmTiles.of("H7"), west = YmTiles.of("H3"), dots = YmTiles.of("D9"), wan = YmTiles.of("W9");
        List<Tile> hand = new ArrayList<>(List.of(white, dots, west, wan)); List<Tile> before = List.copyOf(hand);
        String drawn = mode.equals("null") ? null : mode.equals("missing") ? "not-in-hand" : white.id();
        List<Action> legal = List.of(discard(dots), discard(wan), discard(west)); // Rightmost white itself is not legal.
        assertEquals(discard(west), YmTrustee.choose(hand, drawn, false, legal)); assertEquals(before, hand);
    }

    @Test void fallbackUsesStableRightmostPhysicalCopyNotAnUnownedOrMultiTileAction() {
        Tile first = YmTiles.of("H7"), last = YmTiles.of("H7");
        List<Action> legal = List.of(new Action("DISCARD", "空", List.of()),
                new Action("DISCARD", "外部牌", List.of("foreign")), new Action("DISCARD", "两张", List.of(first.id(), last.id())),
                discard(first), discard(last));
        assertEquals(discard(last), YmTrustee.choose(List.of(first, last), null, false, legal));
        assertNull(YmTrustee.choose(List.of(first, last), null, false, legal.subList(0, 3)));
    }

    @Test void drawAndAcknowledgementRemainTheProvidedLegalActions() {
        Action draw = Action.of("DRAW", "摸牌"), ack = Action.of("ACK", "确认");
        assertSame(draw, YmTrustee.choose(List.of(), null, false, List.of(draw)));
        assertSame(ack, YmTrustee.choose(List.of(), null, false, List.of(ack)));
        assertNull(YmTrustee.choose(List.of(), null, false, List.of()));
    }

    @Test void readyLifecycleNeverTogglesAnAlreadyReadySeatOff() {
        Action ready = Action.of("READY", "准备");
        assertSame(ready, YmTrustee.choose(List.of(), null, false, List.of(ready)));
        assertNull(YmTrustee.choose(List.of(), null, true, List.of(ready)));
    }
}
