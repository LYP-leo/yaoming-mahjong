package com.mahjong.yaoming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mahjong.MahjongApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Boots the real, clean application scan: a standalone controller mock would miss old beans. */
@SpringBootTest(classes = MahjongApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class YmOnlyApplicationTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void dataFile(DynamicPropertyRegistry registry) {
        registry.add("mahjong.data.yaoming-file", () -> storage.resolve("yaoming-rooms.json").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ApplicationContext context;
    @Autowired RequestMappingHandlerMapping mappings;

    @Test void componentScanRegistersOnlyTheYaomingApiAndNoLegacyServicesOrWebsocketBroker() {
        List<String> routes = mappings.getHandlerMethods().keySet().stream()
            .flatMap(mapping -> mapping.getPatternValues().stream()).filter(path -> path.startsWith("/api/")).sorted().toList();
        assertThat(routes).isNotEmpty().allMatch(path -> path.startsWith("/api/yaoming/"));
        assertThat(routes).contains("/api/yaoming/rules", "/api/yaoming/rooms", "/api/yaoming/rooms/{id}/hints",
            "/api/yaoming/rooms/{id}/stream", "/api/yaoming/replays");
        assertThat(context.getBeansOfType(YmController.class)).hasSize(1);
        assertThat(context.getBeansOfType(YmService.class)).hasSize(1);
        ClassLoader loader = getClass().getClassLoader();
        for (String removed : List.of("com.mahjong.api.RoomController", "com.mahjong.api.ApiModels",
            "com.mahjong.service.RoomService", "com.mahjong.service.RuleCatalogStore", "com.mahjong.service.JsonRoomStateStore",
            "com.mahjong.rules.ScoringEngine", "com.mahjong.rules.JapaneseYakuEvaluator", "com.mahjong.domain.RuleSet",
            "com.mahjong.domain.Meld", "com.mahjong.config.WebSocketConfig",
            "org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer",
            "org.springframework.messaging.simp.SimpMessagingTemplate"))
            assertThat(ClassUtils.isPresent(removed, loader)).as("Removed runtime class %s", removed).isFalse();
        assertThat(ClassUtils.isPresent("com.mahjong.domain.Tile", loader)).isTrue();
        assertThat(context.getEnvironment().getProperty("mahjong.data.rules-file")).isNull();
        assertThat(context.getEnvironment().getProperty("mahjong.data.rooms-file")).isNull();
    }

    @ParameterizedTest(name = "removed endpoint {0} {1} returns 404")
    @CsvSource({
        "GET, /api/rules", "GET, /api/rules/presets", "POST, /api/rules", "PUT, /api/rules/old", "DELETE, /api/rules/old",
        "GET, /api/rooms", "POST, /api/rooms", "GET, /api/rooms/old", "POST, /api/rooms/old/join",
        "POST, /api/rooms/old/resume", "POST, /api/rooms/old/ready", "POST, /api/rooms/old/draw",
        "POST, /api/rooms/old/discard", "POST, /api/rooms/old/riichi", "POST, /api/rooms/old/win",
        "POST, /api/rooms/old/abort/nine-terminals", "POST, /api/rooms/old/claim", "POST, /api/rooms/old/kong",
        "POST, /api/rooms/old/trustee", "POST, /api/rooms/old/leave", "GET, /ws/info", "GET, /ws/websocket"
    })
    void legacyEndpointsAreNotReachable(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound());
    }

    @Test void newRulesRoomCreationAuthenticatedViewReadyAndHintsWorkWithSharedTypes() throws Exception {
        mvc.perform(get("/api/yaoming/rules")).andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value("26.9 LTS"))
            .andExpect(jsonPath("$.tiles.length()").value(27));
        JsonNode identity = mapper.readTree(mvc.perform(post("/api/yaoming/rooms").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"仅新版接口测试\",\"playerName\":\"测试玩家\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String id = identity.get("roomId").asText(), player = identity.get("playerId").asText(), token = identity.get("token").asText();
        JsonNode room = mapper.readTree(mvc.perform(get("/api/yaoming/rooms/" + id).param("playerId", player)
                .header("X-Resume-Token", token)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WAITING"))
            .andReturn().getResponse().getContentAsString());
        long before = room.get("version").asLong();
        String command = mapper.writeValueAsString(new YmController.Command(player, null, before, "new-only-ready", "READY", List.of()));
        mvc.perform(post("/api/yaoming/rooms/" + id + "/actions").header("X-Resume-Token", token)
                .contentType(MediaType.APPLICATION_JSON).content(command))
            .andExpect(status().isOk()).andExpect(jsonPath("$.players[0].ready").value(true))
            .andExpect(jsonPath("$.version").value(before + 1));
        mvc.perform(get("/api/yaoming/rooms/" + id + "/hints").param("playerId", player).header("X-Resume-Token", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.analysis.mode").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.version").value(before + 1));
        mvc.perform(get("/api/yaoming/rooms")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
    }

    @Test void sharedCorsAndJsonValidationStillProtectTheNewApi() throws Exception {
        mvc.perform(options("/api/yaoming/rooms").header("Origin", "http://82.156.207.98:5173")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "content-type"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://82.156.207.98:5173"));
        mvc.perform(post("/api/yaoming/rooms").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"playerName\":\"测试玩家\"}"))
            .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
