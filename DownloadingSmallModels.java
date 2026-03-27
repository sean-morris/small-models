import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;

/**
 * Exercise 01 – Download Quantized GGUF Models from Hugging Face
 *
 * Downloads two small language models in GGUF format (4-bit quantized):
 *   - OLMo 1B   (~700 MB)
 *   - Qwen 1.5B (~1 GB)
 *
 */
public class DownloadingSmallModels {

    // Where to save the downloaded models
    private String downloadFolder;

    // The HTTP client we use for all requests
    private HttpClient client;

    // Constructor — sets up the download folder and HTTP client
    public DownloadingSmallModels(String downloadFolder) {
        this.downloadFolder = downloadFolder;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Downloads both GGUF model files.
     * This is the main workflow method.
     */
    public void download() throws Exception {
        Path folder = Paths.get(downloadFolder).toAbsolutePath();
        Files.createDirectories(folder);
        System.out.println("Download folder: " + folder + "\n");

        // Model 1: OLMo 1B
        // downloadModel(
        //     "nopperl/OLMo-1B-GGUF",
        //     "OLMo-1B.Q4_K_M.gguf",
        //     "OLMo 1B (4-bit)",
        //     folder
        // );

        // Model 2: Qwen 2.5 1.5B
        // downloadModel(
        //     "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
        //     "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        //     "Qwen 2.5 1.5B Instruct (4-bit)",
        //     folder
        // );

        downloadModel(
            "HauhauCS/Qwen3.5-2B-Uncensored-HauhauCS-Aggressive",
            "Qwen3.5-2B-Uncensored-HauhauCS-Aggressive-BF16.gguf",
            "Qwen3.5-2B-Uncensored-HauhauCS-Aggressive",
            folder
        );

        System.out.println("Done! Models saved to: " + folder);
    }

    /**
     * Downloads a single GGUF model file from Hugging Face.
     *
     * @param repoId    the Hugging Face repo (e.g. "Qwen/Qwen2.5-1.5B-Instruct-GGUF")
     * @param fileName  the specific file to download (e.g. "qwen2.5-1.5b-instruct-q4_k_m.gguf")
     * @param name      a friendly name to print
     * @param folder    the local folder to save into
     */
    private void downloadModel(String repoId, String fileName, String name, Path folder)
            throws Exception {

        System.out.println("=== " + name + " ===");

        // Print some info about the model from the HF API
        printModelInfo(repoId);

        Path targetFile = folder.resolve(fileName);

        // Build the download URL
        String url = "https://huggingface.co/" + repoId + "/resolve/main/" + fileName;
        System.out.println("Downloading from: " + url);

        // Send the request and stream the response to a file
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(
                request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            System.out.println("ERROR: Got HTTP " + response.statusCode());
            return;
        }

        // Get total size so we can show progress
        long totalBytes = response.headers()
                .firstValueAsLong("Content-Length")
                .orElse(-1);

        // Read the response and write it to disk
        InputStream in = response.body();
        OutputStream out = new BufferedOutputStream(Files.newOutputStream(targetFile));
        byte[] buffer = new byte[65536]; // 64 KB chunks
        long downloaded = 0;
        int bytesRead;

        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
            downloaded += bytesRead;
            printProgress(downloaded, totalBytes);
        }

        in.close();
        out.close();

        System.out.println(); // newline after progress bar
        System.out.println("Saved: " + targetFile);
        System.out.println("Size:  " + formatSize(Files.size(targetFile)) + "\n");
    }

    /**
     * Fetches and prints basic info about a model from the HF API.
     */
    private void printModelInfo(String repoId) throws Exception {
        String url = "https://huggingface.co/api/models/" + repoId;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = client.send(
                request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            String json = response.body();
            System.out.println("  Repo     : " + repoId);
            System.out.println("  Author   : " + grabValue(json, "author"));
            System.out.println("  Downloads: " + grabValue(json, "downloads"));
        }
        System.out.println();
    }

    /**
     * Prints a simple progress bar to the console.
     * Uses \r to overwrite the same line.
     */
    private void printProgress(long downloaded, long total) {
        if (total <= 0) {
            System.out.print("\r  " + formatSize(downloaded) + " downloaded...");
            System.out.flush();
            return;
        }

        int percent = (int) (downloaded * 100 / total);
        int barLength = 30;
        int filled = percent * barLength / 100;

        StringBuilder bar = new StringBuilder("\r  [");
        for (int i = 0; i < barLength; i++) {
            if (i < filled) bar.append('#');
            else bar.append(' ');
        }
        bar.append("] ");
        bar.append(formatSize(downloaded));
        bar.append(" / ");
        bar.append(formatSize(total));
        bar.append("  (");
        bar.append(percent);
        bar.append("%)");

        System.out.print(bar);
        System.out.flush();
    }

    /**
     * Grabs a simple value from JSON text by looking for "key": value.
     * This is a quick-and-dirty approach — works for flat JSON fields.
     */
    private String grabValue(String json, String key) {
        // Look for "key": "stringValue" first
        String pattern1 = "\"" + key + "\":\"";
        int i = json.indexOf(pattern1);
        if (i >= 0) {
            int start = i + pattern1.length();
            int end = json.indexOf("\"", start);
            return json.substring(start, end);
        }

        // Look for "key": numberValue
        String pattern2 = "\"" + key + "\":";
        i = json.indexOf(pattern2);
        if (i >= 0) {
            int start = i + pattern2.length();
            int end = start;
            while (end < json.length() && Character.isDigit(json.charAt(end))) {
                end++;
            }
            if (end > start) {
                return json.substring(start, end);
            }
        }

        return "unknown";
    }

    /**
     * Converts a byte count to a human-readable string like "700.5 MB".
     */
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // --- Main ---
    public static void main(String[] args) throws Exception {
        System.out.println("========================================");
        System.out.println("  Exercise 01 - Download GGUF Models");
        System.out.println("========================================\n");

        DownloadingSmallModels downloader = new DownloadingSmallModels("downloaded_models");
        downloader.download();
    }
}
