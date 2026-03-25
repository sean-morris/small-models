import java.net.URI;
import java.net.http.*;
import java.nio.file.*;

/**
 * Exercise 02 – Temperature & Sampling (Local GGUF Models)
 *
 * Demonstrates how the temperature parameter affects text generation:
 *   - Low  (0.1) : nearly deterministic, safe, repetitive
 *   - Mid  (0.7) : balanced creativity
 *   - High (1.5) : diverse / surprising, sometimes incoherent
 *
 * Uses the GGUF models downloaded in Exercise 01, served locally
 * by llama-server (from llama.cpp). No API tokens needed.
 *
 * We need to do this:
 * mkdir -p tools/llama.cpp
 * cd tools/llama.cpp
 * 
 * # download prebuilt Ubuntu x64 release archive
 * wget https://github.com/ggml-org/llama.cpp/releases/download/b8514/llama-b8514-bin-ubuntu-x64.tar.gz -O llama-prebuilt.tar.gz
 * tar -xzf llama-prebuilt.tar.gz
 *
 * # verify binaries exist
 * ./llama-b8514/llama-cli --help
 * ./llama-b8514/llama-server --help
 */
public class TemperatureSampling {

    // Model GGUF files (relative to download dir)
    private final String[][] models = {
        // { subdirectory, gguf filename, friendly name }
        { "nopperl__OLMo-1B-GGUF",              "OLMo-1B.Q4_K_M.gguf",                "OLMo 1B" },
        { "Qwen__Qwen2.5-1.5B-Instruct-GGUF",   "qwen2.5-1.5b-instruct-q4_k_m.gguf",  "Qwen 2.5 1.5B Instruct" },
    };

    private final double[] temperatures = {0.1, 0.5, 0.9, 1.5};
    private final int maxTokens = 80;
    private final int serverPort = 8090;
    private final String serverUrl = "http://127.0.0.1:" + serverPort;

    private final String prompt =
            "Explain why the sky is blue in one paragraph:";

    private final HttpClient httpClient;
    private final String modelsDir;

    public TemperatureSampling(String modelsDir) {
        this.modelsDir = modelsDir;
        this.httpClient = HttpClient.newHttpClient();
    }

    // Main entry point
    public static void main(String[] args) throws Exception {
        String modelsDir = args.length > 0 ? args[0] : "downloaded_models";
        Exercise02TemperatureSampling app = new Exercise02TemperatureSampling(modelsDir);
        app.run();
    }

    // Runs the full experiment from start to finish
    public void run() throws Exception {
        System.out.println("==================================================");
        System.out.println("  Exercise 02 - Temperature & Sampling (Local)");
        System.out.println("==================================================\n");

        checkLlamaServer();

        Path basePath = Paths.get(modelsDir).toAbsolutePath();
        System.out.println("Models dir : " + basePath);
        System.out.println("Prompt     : \"" + prompt + "\"");
        System.out.println("Max tokens : " + maxTokens + "\n");

        for (String[] model : models) {
            Path ggufPath = basePath.resolve(model[0]).resolve(model[1]);
            String friendlyName = model[2];

            System.out.println("--------------------------------------------------");
            System.out.println("  Model: " + friendlyName);
            System.out.println("  File : " + ggufPath.getFileName());
            System.out.println("--------------------------------------------------\n");

            if (!Files.exists(ggufPath)) {
                System.out.println("  GGUF file not found: " + ggufPath);
                System.out.println("  Run Exercise01 first to download the models.\n");
                continue;
            }

            // Start llama-server for this model
            Process server = startServer(ggufPath);
            try {
                waitForServer();

                for (double temp : temperatures) {
                    System.out.printf("-- temperature = %.1f --%n", temp);
                    try {
                        String output = generate(prompt, temp, maxTokens);
                        System.out.println(output.strip());
                    } catch (Exception e) {
                        System.out.println("  Error: " + e.getMessage());
                    }
                    System.out.println();
                }
            } finally {
                stopServer(server);
            }
        }

        System.out.println("Observation: lower temperatures produce more predictable text,");
        System.out.println("while higher temperatures increase randomness and creativity.");
    }

    // Sends a prompt to llama-server and gets generated text back

    private String generate(String prompt, double temperature,
                                   int maxTokens) throws Exception {
        String body = String.format(
            "{\"prompt\":\"%s\","
            + "\"temperature\":%.2f,"
            + "\"n_predict\":%d,"
            + "\"stop\":[\"\\n\\n\"]}",
            escapeJson(prompt), temperature, maxTokens);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl + "/completion"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

        if (res.statusCode() != 200) {
            throw new RuntimeException("HTTP " + res.statusCode() + " : " + res.body());
        }

        return extractContent(res.body());
    }

    // Starts llama-server for one model file

    private Process startServer(Path ggufPath) throws Exception {
        System.out.println("Starting llama-server on port " + serverPort + " ...");
        ProcessBuilder pb = new ProcessBuilder(
            "llama-server",
            "-m", ggufPath.toString(),
            "--port", String.valueOf(serverPort),
            "--ctx-size", "2048",
            "--log-disable"
        );
        pb.redirectErrorStream(true);
        // Send server output to /dev/null so it doesn't clutter student console
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        return pb.start();
    }

    private void waitForServer() throws Exception {
        System.out.print("Waiting for server to be ready ");
        for (int i = 0; i < 60; i++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(serverUrl + "/health"))
                        .GET()
                        .build();
                HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200 && res.body().contains("ok")) {
                    System.out.println(" ready!\n");
                    return;
                }
            } catch (Exception ignored) {
                // server not up yet
            }
            System.out.print(".");
            Thread.sleep(1000);
        }
        throw new RuntimeException("llama-server did not start within 60 seconds");
    }

    private void stopServer(Process server) {
        if (server != null && server.isAlive()) {
            server.destroyForcibly();
            System.out.println("(server stopped)\n");
        }
    }

    // JSON helpers (no external libraries)

    /** Extract the "content" value from llama-server's /completion response. */
    private String extractContent(String responseBody) {
        return extractJsonString(responseBody, "content");
    }

    private String extractJsonString(String json, String key) {
        int keyIdx = json.indexOf("\"" + key + "\"");
        if (keyIdx < 0) throw new RuntimeException("No \"" + key + "\" in: " + json);
        int colon = json.indexOf(':', keyIdx);
        int open = json.indexOf('"', colon + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = open + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case 'n':  sb.append('\n'); break;
                    case 't':  sb.append('\t'); break;
                    case 'r':  sb.append('\r'); break;
                    default:   sb.append(c).append(next); break;
                }
                i++;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // Checks that llama-server is installed before running

    private void checkLlamaServer() {
        try {
            Process p = new ProcessBuilder("which", "llama-server")
                    .redirectErrorStream(true).start();
            int code = p.waitFor();
            if (code != 0) throw new RuntimeException();
        } catch (Exception e) {
            System.err.println("ERROR: llama-server is not installed.");
            System.err.println("  Install llama.cpp:  brew install llama.cpp");
            System.err.println("  Then re-run this exercise.");
            System.exit(1);
        }
    }
}
