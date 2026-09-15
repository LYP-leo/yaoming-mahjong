package com.mahjong.yaoming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** JSON-lines process. Standard output is exclusively protocol JSON, never log messages. */
public final class YmTrainingMain {
    private final ObjectMapper json = new ObjectMapper();
    private final Map<Integer, YmTrainingEnvironment> environments = new ConcurrentHashMap<>();
    private final ExecutorService workers;
    private final int workerCount;
    private static final int MAX_ENVS = 4096;

    private YmTrainingMain(int count) { workerCount = count; workers = count > 1 ? Executors.newFixedThreadPool(count) : null; }

    public static void main(String[] args) throws Exception {
        int count = 1;
        if (args.length != 0) {
            if (args.length != 2 || !args[0].equals("--workers")) throw new IllegalArgumentException("Usage: YmTrainingMain [--workers 1..64]");
            count = Integer.parseInt(args[1]);
        }
        if (count < 1 || count > 64) throw new IllegalArgumentException("workers must be 1..64");
        YmTrainingMain server = new YmTrainingMain(count);
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             PrintWriter output = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true)) {
            String line;
            while ((line = input.readLine()) != null) {
                boolean close = false;
                Object result;
                try {
                    JsonNode request = server.json.readTree(line);
                    close = request != null && request.path("cmd").asText().equals("close");
                    result = server.dispatch(request);
                } catch (Exception failure) { result = error(failure); }
                output.println(server.json.writeValueAsString(result));
                if (close) break;
            }
        } finally { if (server.workers != null) server.workers.shutdownNow(); }
    }

    private Object dispatch(JsonNode request) throws Exception {
        if (request == null || !request.isObject()) throw new IllegalArgumentException("Request must be a JSON object");
        String command = request.path("cmd").asText("");
        if (command.equals("hello")) return Map.of("ok", true, "protocol", 1, "actionSize", YmTrainingEncoding.ACTION_SIZE,
            "observationSize", YmTrainingEncoding.OBSERVATION_SIZE, "workers", workerCount, "rules",
            Arrays.stream(YmRules.values()).map(rule -> Map.of("id", rule.id(), "playerCount", rule.playerCount(),
                "minimumFan", rule.minimumFan(), "totalRounds", rule.totalRounds(), "tileCount", rule.tileCount())).toList());
        if (command.equals("close")) return Map.of("ok", true);
        if (command.equals("batch")) return batch(request);
        return single(request);
    }

    private Object batch(JsonNode request) throws Exception {
        JsonNode list = request.get("requests");
        if (list == null || !list.isArray() || list.size() > MAX_ENVS) throw new IllegalArgumentException("batch requests must be an array, max4096");
        Set<Integer> seen = new HashSet<>();
        List<JsonNode> requests = new ArrayList<>();
        // Validate the whole envelope before starting work; no racing requests for one env.
        for (JsonNode child : list) {
            if (!child.isObject() || !Set.of("reset", "step", "baseline").contains(child.path("cmd").asText())) throw new IllegalArgumentException("Batch supports reset/step/baseline only");
            if (!seen.add(environmentId(child))) throw new IllegalArgumentException("Duplicate env in batch");
            requests.add(child);
        }
        List<Object> results = new ArrayList<>();
        if (workers == null) for (JsonNode child : requests) results.add(safeSingle(child));
        else {
            List<Future<Object>> futures = new ArrayList<>();
            for (JsonNode child : requests) futures.add(workers.submit(() -> safeSingle(child)));
            for (Future<Object> future : futures) results.add(future.get());
        }
        return Map.of("ok", true, "results", results);
    }

    private Object safeSingle(JsonNode request) { try { return single(request); } catch (Exception failure) { return error(failure); } }

    private Object single(JsonNode request) {
        int id = environmentId(request);
        String command = request.path("cmd").asText();
        if (command.equals("reset")) {
            if (!request.has("seed") || !request.get("seed").isIntegralNumber() || !request.get("seed").canConvertToLong()) throw new IllegalArgumentException("reset requires a signed64-bit seed");
            if (!request.has("rule") || !request.get("rule").isTextual()) throw new IllegalArgumentException("reset requires rule");
            if (!environments.containsKey(id) && environments.size() >= MAX_ENVS) throw new IllegalArgumentException("Too many environments");
            YmTrainingEnvironment environment = new YmTrainingEnvironment(id, request.get("rule").asText(), request.get("seed").asLong());
            environments.put(id, environment); return environment.state();
        }
        YmTrainingEnvironment environment = environments.get(id);
        if (environment == null) throw new IllegalArgumentException("Unknown env; reset first");
        if (command.equals("baseline")) return Map.of("ok", true, "action", environment.baseline(policy(request)));
        if (!command.equals("step")) throw new IllegalArgumentException("Unknown command " + command);
        if (request.has("action") == request.has("policy")) throw new IllegalArgumentException("step requires exactly one of action or policy");
        if (request.has("policy")) return environment.step(policy(request));
        if (!request.get("action").isIntegralNumber() || !request.get("action").canConvertToInt()) throw new IllegalArgumentException("action must be an integer");
        return environment.step(request.get("action").asInt());
    }

    private static int environmentId(JsonNode request) {
        if (!request.has("env") || !request.get("env").isIntegralNumber() || !request.get("env").canConvertToInt()
            || request.get("env").asInt() < 0 || request.get("env").asInt() >= MAX_ENVS) throw new IllegalArgumentException("env must be an integer in0..4095");
        return request.get("env").asInt();
    }
    private static String policy(JsonNode request) {
        if (!request.has("policy") || !request.get("policy").isTextual()) throw new IllegalArgumentException("policy must be heuristic/random/tsumogiri");
        return request.get("policy").asText();
    }
    private static Map<String, Object> error(Exception failure) { return Map.of("ok", false, "error", failure.getClass().getSimpleName() + ": " + failure.getMessage()); }
}
