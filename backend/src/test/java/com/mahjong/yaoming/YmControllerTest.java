package com.mahjong.yaoming;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.NoSuchElementException;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class YmControllerTest {
    @Test void streamAuthErrorRemainsJson400EvenWhenOnlyEventStreamIsAccepted() throws Exception {
        YmService service=mock(YmService.class);
        when(service.stream("123456","player","wrong")).thenThrow(new IllegalArgumentException("玩家身份无效"));
        MockMvcBuilders.standaloneSetup(new YmController(service)).build().perform(get("/api/yaoming/rooms/123456/stream")
                .param("playerId","player").header("X-Resume-Token","wrong").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("玩家身份无效"));
    }
    @Test void streamMissingHeaderIsJson400WithoutCallingService() throws Exception {
        YmService service=mock(YmService.class);
        MockMvcBuilders.standaloneSetup(new YmController(service)).build().perform(get("/api/yaoming/rooms/123456/stream")
                .param("playerId","player").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        verifyNoInteractions(service);
    }
    @Test void streamMissingRoomIsJson404() throws Exception {
        YmService service=mock(YmService.class);
        when(service.stream("123456","player","token")).thenThrow(new NoSuchElementException("房间不存在"));
        MockMvcBuilders.standaloneSetup(new YmController(service)).build().perform(get("/api/yaoming/rooms/123456/stream")
                .param("playerId","player").header("X-Resume-Token","token").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}
