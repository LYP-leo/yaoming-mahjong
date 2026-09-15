package com.mahjong.yaoming;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.MissingRequestHeaderException;
import java.util.*;
import static com.mahjong.yaoming.YmViews.*;

@RestController
@RequestMapping("/api/yaoming")
public class YmController {
    private final YmService service;
    public YmController(YmService service) { this.service = service; }
    public record Create(@NotBlank @Size(max=30) String name, @NotBlank @Size(max=20) String playerName, @Size(max=60) String ruleId) {
        public Create(String name, String playerName) { this(name, playerName, null); }
    }
    public record Join(@NotBlank @Size(max=20) String playerName) {}
    public record Resume(@NotBlank @Size(max=100) String token) {}
    public record Command(@NotBlank String playerId, String token, @PositiveOrZero long version,
                          @NotBlank @Size(max=100) String requestId, @NotBlank String type, List<String> tileIds) {}
    @GetMapping("/rules") public Rules rules(@RequestParam(required=false) String ruleId) { return service.rules(ruleId); }
    public Rules rules() { return service.rules(); }
    @GetMapping("/rulesets") public List<Rules> rulesets() { return service.rulesets(); }
    @GetMapping("/rooms") public List<Summary> list() { return service.list(); }
    @PostMapping("/rooms") @ResponseStatus(HttpStatus.CREATED)
    public Identity create(@Valid @RequestBody Create body) { return service.create(body.name(), body.playerName(), body.ruleId()); }
    @PostMapping("/rooms/{id}/join") public Identity join(@PathVariable String id, @Valid @RequestBody Join body) { return service.join(id, body.playerName()); }
    @PostMapping("/rooms/{id}/resume") public Identity resume(@PathVariable String id, @Valid @RequestBody Resume body) { return service.resume(id, body.token()); }
    @GetMapping("/rooms/{id}") public RoomView view(@PathVariable String id, @RequestParam String playerId,
                                                  @RequestHeader("X-Resume-Token") String token) { return service.view(id, playerId, token); }
    @GetMapping("/rooms/{id}/hints") public YmService.HintView hints(@PathVariable String id, @RequestParam String playerId,
                                                   @RequestHeader("X-Resume-Token") String token) { return service.hints(id, playerId, token); }
    @GetMapping(value="/rooms/{id}/stream", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@PathVariable String id, @RequestParam String playerId,
                                             @RequestHeader("X-Resume-Token") String token) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM).body(service.stream(id, playerId, token));
    }
    @GetMapping("/replays") public YmService.ReplayList replays(@RequestParam String roomId, @RequestParam String playerId,
                                                              @RequestHeader("X-Resume-Token") String token) {
        return service.replays(roomId, playerId, token);
    }
    @GetMapping("/replays/{id}/{round}") public YmReplay.HandRecord replay(@PathVariable String id, @PathVariable int round,
                        @RequestParam String playerId, @RequestHeader("X-Resume-Token") String token) {
        return service.replay(id, round, playerId, token);
    }
    @PostMapping("/rooms/{id}/actions") public RoomView action(@PathVariable String id, @Valid @RequestBody Command body,
                                                               @RequestHeader(value="X-Resume-Token", required=false) String token) {
        return service.action(id, body.playerId(), token != null ? token : body.token(), body.version(), body.requestId(), body.type(), body.tileIds());
    }
    @ExceptionHandler(YmService.VersionConflict.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(YmService.VersionConflict error) { return Map.of("code", "STALE_VERSION", "message", error.getMessage()); }
    // SSE clients accept text/event-stream, but errors must still be unambiguous JSON.
    // Explicit content type prevents the negotiated stream type turning an auth error into HTTP 500.
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, MissingRequestHeaderException.class})
    public ResponseEntity<Map<String, String>> invalid(Exception error) {
        String message = error instanceof MissingRequestHeaderException ? "缺少玩家身份请求头，请重新连接" : error.getMessage();
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "INVALID_ACTION", "message", message == null ? "请求无效" : message));
    }
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> missing(NoSuchElementException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "NOT_FOUND", "message", error.getMessage()));
    }
}
