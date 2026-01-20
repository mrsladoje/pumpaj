package com.pumpaj.evropo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.core.io.ClassPathResource;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

@Service
public class AnalyserService {

    @Value("${python.script.path.analyzer:scripts/gemini_text_analysis.py}")
    private String analyzerScriptPath;

    @Value("${python.executable.path:python}")
    private String pythonPath;

    @Autowired
    private DataProcessingService dataProcessingService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Coordinates> serbianCityCoordinates;

    public AnalyserService() {
        this.serbianCityCoordinates = initializeSerbianCityCoordinates();
    }

    public void analyseAndProcess(String url, String source) {
        try {
            File tempScript = extractScriptFromClasspath();
            String[] command = new String[]{
                    pythonPath,
                    tempScript.getAbsolutePath(),
                    "--url", url,
                    "--source", source
            };

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            StringBuilder output = new StringBuilder();
            String line;

            boolean jsonStarted = false;
            StringBuilder jsonBuilder = new StringBuilder();

            while ((line = reader.readLine()) != null) {
                // Check if this is the start of JSON output
                if (line.contains("--- Gemini Analysis Result ---")) {
                    jsonStarted = true;
                    continue;
                }

                // Check if this is the end of JSON output
                if (line.contains("--- End Analysis Result ---")) {
                    break;
                }

                // Collect JSON lines
                if (jsonStarted) {
                    jsonBuilder.append(line).append("\n");
                }

                output.append(line).append("\n");
            }

            process.waitFor();
            tempScript.delete();

            // Parse and process JSON response
            String jsonOutput = jsonBuilder.toString().trim();
            if (!jsonOutput.isEmpty()) {
                // Process JSON response to create smaller JSON objects
                processJsonResponse(jsonOutput, source);
            } else {
                System.out.println("No JSON output found for URL: " + url);
            }

        } catch (Exception e) {
            System.err.println("Error analyzing URL " + url + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void processJsonResponse(String jsonResponse, String source) {
        try {
            ObjectNode mainJson = (ObjectNode) objectMapper.readTree(jsonResponse);

            // Create protest JSON if conditions are met
            if (mainJson.has("analysis") &&
                    mainJson.path("analysis").path("mentions_protest").asBoolean() &&
                    mainJson.path("analysis").path("protest_info").isObject()) {

                ObjectNode protestInfo = (ObjectNode) mainJson.path("analysis").path("protest_info");
                String organizer = protestInfo.path("organizer").asText("");
                String location = protestInfo.path("location").asText("");
                String date = protestInfo.path("date").asText("");

                // Check if all fields are present and not "unknown"
                if (!organizer.isEmpty() && !organizer.equalsIgnoreCase("unknown") &&
                        !location.isEmpty() && !location.equalsIgnoreCase("unknown") &&
                        !date.isEmpty() && !date.equalsIgnoreCase("unknown")) {

                    ObjectNode protestJson = objectMapper.createObjectNode();
                    protestJson.put("organizer", organizer);
                    protestJson.put("location", location);
                    protestJson.put("date", date);

                    // Add count information
                    if (protestInfo.has("count")) {
                        protestJson.set("count", protestInfo.get("count"));
                    }

                    // Add coordinates if location is a known Serbian city
                    addCoordinatesToProtest(protestJson, location);

                    // Process and save the protest data
                    dataProcessingService.processProtestJson(protestJson);
                }
            }

            // Create day JSON
            ObjectNode dayJson = objectMapper.createObjectNode();
            if (mainJson.has("date_of_news_issue")) {
                dayJson.put("date", mainJson.get("date_of_news_issue").asText());
            }

            // Add canonical source to dayJson for source-aware processing
            String canonicalSource = mapSourceToCanonical(source);
            dayJson.put("source", canonicalSource);

            if (mainJson.has("state_driven_messaging")) {
                dayJson.put("state_driven_messaging", mainJson.get("state_driven_messaging").asInt());
            }
            if (mainJson.has("pro_student_messaging")) {
                dayJson.put("pro_student_messaging", mainJson.get("pro_student_messaging").asInt());
            }
            if (mainJson.has("student_mentions")) {
                dayJson.set("student_mentions", mainJson.get("student_mentions"));
            }
            if (mainJson.has("state_mentions")) {
                dayJson.set("state_mentions", mainJson.get("state_mentions"));
            }
            if (mainJson.has("propaganda_count")) {
                dayJson.put("propaganda_count", mainJson.get("propaganda_count").asInt());
            }
            if (mainJson.has("pro_protest_count")) {
                dayJson.put("pro_protest_count", mainJson.get("pro_protest_count").asInt());
            }

            // Process and save the day data
            dataProcessingService.processDayJson(dayJson);

        } catch (Exception e) {
            System.err.println("Error processing JSON response: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Maps source website strings to canonical source categories
     */
    private String mapSourceToCanonical(String source) {
        if (source == null) return "unknown";
        switch (source.toLowerCase()) {
            case "informer.rs":
                return "government";
            case "021.rs":
                return "independent";
            default:
                return "unknown";
        }
    }

    private File extractScriptFromClasspath() throws IOException {
        ClassPathResource resource = new ClassPathResource(analyzerScriptPath);
        File tempFile = File.createTempFile("analyzer", ".py");
        tempFile.deleteOnExit();

        try (InputStream is = resource.getInputStream()) {
            Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return tempFile;
    }

    private void addCoordinatesToProtest(ObjectNode protestJson, String location) {
        // First try to match exact city name
        Coordinates coordinates = serbianCityCoordinates.get(location);

        // If no match, try case-insensitive matching
        if (coordinates == null) {
            for (Map.Entry<String, Coordinates> entry : serbianCityCoordinates.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(location)) {
                    coordinates = entry.getValue();
                    break;
                }
            }
        }

        // If we found coordinates, add them to the JSON
        if (coordinates != null) {
            protestJson.put("x", coordinates.getX());
            protestJson.put("y", coordinates.getY());
        } else {
            System.out.println("Warning: No coordinates found for location: " + location);
        }
    }

    private Map<String, Coordinates> initializeSerbianCityCoordinates() {
        Map<String, Coordinates> coordinatesMap = new HashMap<>();

        coordinatesMap.put("Beograd", new Coordinates(44.8125, 20.4612));
        coordinatesMap.put("Novi Sad", new Coordinates(45.2671, 19.8335));
        coordinatesMap.put("Niš", new Coordinates(43.3209, 21.8958));
        coordinatesMap.put("Kragujevac", new Coordinates(44.0128, 20.9114));
        coordinatesMap.put("Subotica", new Coordinates(46.1005, 19.6650));
        coordinatesMap.put("Zrenjanin", new Coordinates(45.3814, 20.3822));
        coordinatesMap.put("Pančevo", new Coordinates(44.8708, 20.6403));
        coordinatesMap.put("Čačak", new Coordinates(43.8914, 20.3497));
        coordinatesMap.put("Kruševac", new Coordinates(43.5800, 21.3339));
        coordinatesMap.put("Kraljevo", new Coordinates(43.7256, 20.6887));
        coordinatesMap.put("Novi Pazar", new Coordinates(43.1367, 20.5122));
        coordinatesMap.put("Smederevo", new Coordinates(44.6640, 20.9274));
        coordinatesMap.put("Leskovac", new Coordinates(42.9981, 21.9461));
        coordinatesMap.put("Užice", new Coordinates(43.8556, 19.8425));
        coordinatesMap.put("Vranje", new Coordinates(42.5514, 21.8997));
        coordinatesMap.put("Valjevo", new Coordinates(44.2708, 19.8903));
        coordinatesMap.put("Šabac", new Coordinates(44.7545, 19.6936));
        coordinatesMap.put("Sombor", new Coordinates(45.7742, 19.1122));
        coordinatesMap.put("Požarevac", new Coordinates(44.6178, 21.1872));
        coordinatesMap.put("Pirot", new Coordinates(43.1531, 22.5861));
        coordinatesMap.put("Zaječar", new Coordinates(43.9142, 22.2842));
        coordinatesMap.put("Kikinda", new Coordinates(45.8286, 20.4606));
        coordinatesMap.put("Sremska Mitrovica", new Coordinates(44.9744, 19.6114));
        coordinatesMap.put("Jagodina", new Coordinates(43.9771, 21.2610));
        coordinatesMap.put("Vršac", new Coordinates(45.1214, 21.3019));
        coordinatesMap.put("Bor", new Coordinates(44.0749, 22.0959));
        coordinatesMap.put("Ruma", new Coordinates(45.0089, 19.8222));
        coordinatesMap.put("Bačka Palanka", new Coordinates(45.2517, 19.3878));
        coordinatesMap.put("Prokuplje", new Coordinates(43.2342, 21.5878));
        coordinatesMap.put("Inđija", new Coordinates(45.0481, 20.0822));
        coordinatesMap.put("Lazarevac", new Coordinates(44.3808, 20.2589));
        coordinatesMap.put("Aranđelovac", new Coordinates(44.3069, 20.5617));
        coordinatesMap.put("Obrenovac", new Coordinates(44.6531, 20.2011));
        coordinatesMap.put("Gornji Milanovac", new Coordinates(44.0239, 20.4611));
        coordinatesMap.put("Vrbas", new Coordinates(45.5714, 19.6386));
        coordinatesMap.put("Bečej", new Coordinates(45.6183, 20.0308));
        coordinatesMap.put("Mladenovac", new Coordinates(44.4358, 20.6961));
        coordinatesMap.put("Smederevska Palanka", new Coordinates(44.3642, 20.9578));
        coordinatesMap.put("Paraćin", new Coordinates(43.8606, 21.4083));
        coordinatesMap.put("Temerin", new Coordinates(45.4078, 19.8881));
        coordinatesMap.put("Loznica", new Coordinates(44.5333, 19.2236));
        coordinatesMap.put("Kula", new Coordinates(45.6108, 19.5256));
        coordinatesMap.put("Stara Pazova", new Coordinates(44.9842, 20.1589));
        coordinatesMap.put("Knjaževac", new Coordinates(43.5667, 22.2583));
        coordinatesMap.put("Surčin", new Coordinates(44.8006, 20.2800));
        coordinatesMap.put("Senta", new Coordinates(45.9303, 20.0897));
        coordinatesMap.put("Apatin", new Coordinates(45.6717, 18.9822));
        coordinatesMap.put("Negotin", new Coordinates(44.2264, 22.5306));
        coordinatesMap.put("Futog", new Coordinates(45.2597, 19.7097));
        coordinatesMap.put("Veternik", new Coordinates(45.2583, 19.7656));
        coordinatesMap.put("Ćuprija", new Coordinates(43.9294, 21.3692));
        coordinatesMap.put("Ivanjica", new Coordinates(43.5825, 20.2311));
        coordinatesMap.put("Bačka Topola", new Coordinates(45.8153, 19.6303));
        coordinatesMap.put("Priboj", new Coordinates(43.5817, 19.5250));
        coordinatesMap.put("Požega", new Coordinates(43.8467, 20.0367));
        coordinatesMap.put("Žabalj", new Coordinates(45.3711, 20.0586));
        coordinatesMap.put("Kuršumlija", new Coordinates(43.1400, 21.2733));
        coordinatesMap.put("Srbobran", new Coordinates(45.5489, 19.7983));
        coordinatesMap.put("Sjenica", new Coordinates(43.2722, 19.9972));
        coordinatesMap.put("Kovin", new Coordinates(44.7472, 20.9769));
        coordinatesMap.put("Vlasotince", new Coordinates(42.9608, 22.1275));
        coordinatesMap.put("Bujanovac", new Coordinates(42.4617, 21.7683));
        coordinatesMap.put("Aleksinac", new Coordinates(43.5403, 21.7058));
        coordinatesMap.put("Šid", new Coordinates(45.1250, 19.2275));
        coordinatesMap.put("Kanjiža", new Coordinates(46.0672, 20.0500));
        coordinatesMap.put("Velika Plana", new Coordinates(44.3356, 21.0783));
        coordinatesMap.put("Trstenik", new Coordinates(43.6192, 21.0017));
        coordinatesMap.put("Petrovaradin", new Coordinates(45.2472, 19.8764));
        coordinatesMap.put("Lebane", new Coordinates(42.9222, 21.7411));
        coordinatesMap.put("Odžaci", new Coordinates(45.5075, 19.2583));
        coordinatesMap.put("Kovačica", new Coordinates(45.1122, 20.6197));
        coordinatesMap.put("Beočin", new Coordinates(45.2464, 19.7228));
        coordinatesMap.put("Bela Crkva", new Coordinates(44.8983, 21.4167));
        coordinatesMap.put("Ada", new Coordinates(45.7994, 20.1264));
        coordinatesMap.put("Novi Kneževac", new Coordinates(46.0492, 20.0906));
        coordinatesMap.put("Sremski Karlovci", new Coordinates(45.2039, 19.9328));
        coordinatesMap.put("Bajina Bašta", new Coordinates(43.9708, 19.5675));
        coordinatesMap.put("Žitište", new Coordinates(45.4867, 20.5489));
        coordinatesMap.put("Titel", new Coordinates(45.2050, 20.2925));
        coordinatesMap.put("Kladovo", new Coordinates(44.6111, 22.6114));
        coordinatesMap.put("Novi Bečej", new Coordinates(45.5994, 20.1314));
        coordinatesMap.put("Tutin", new Coordinates(42.9911, 20.3314));
        coordinatesMap.put("Plandište", new Coordinates(45.2275, 21.1203));
        coordinatesMap.put("Raška", new Coordinates(43.2900, 20.6111));
        coordinatesMap.put("Majdanpek", new Coordinates(44.4228, 21.9369));
        coordinatesMap.put("Vladičin Han", new Coordinates(42.7086, 22.0608));
        coordinatesMap.put("Sokobanja", new Coordinates(43.6444, 21.8722));
        coordinatesMap.put("Sečanj", new Coordinates(45.3667, 20.7731));
        coordinatesMap.put("Ub", new Coordinates(44.4578, 20.0703));
        coordinatesMap.put("Svrljig", new Coordinates(43.4131, 22.1239));
        coordinatesMap.put("Crvenka", new Coordinates(45.6917, 19.4656));
        coordinatesMap.put("Doljevac", new Coordinates(43.2097, 21.8131));
        coordinatesMap.put("Boljevac", new Coordinates(43.8275, 21.9519));
        coordinatesMap.put("Lajkovac", new Coordinates(44.3661, 20.1842));
        coordinatesMap.put("Bač", new Coordinates(45.3917, 19.2369));
        coordinatesMap.put("North Mitrovica", new Coordinates(42.8972, 20.8667));
        coordinatesMap.put("Aleksandrovac", new Coordinates(43.4572, 21.0469));
        coordinatesMap.put("Vrnjačka Banja", new Coordinates(43.6225, 20.8922));
        coordinatesMap.put("Babušnica", new Coordinates(43.0658, 22.4117));
        coordinatesMap.put("Krupanj", new Coordinates(44.3667, 19.3611));
        coordinatesMap.put("Svilajnac", new Coordinates(44.2322, 21.1964));
        coordinatesMap.put("Bela Palanka", new Coordinates(43.2186, 22.3117));
        coordinatesMap.put("Brus", new Coordinates(43.3867, 21.0300));
        coordinatesMap.put("Dimitrovgrad", new Coordinates(43.0164, 22.7833));
        coordinatesMap.put("Čoka", new Coordinates(45.9411, 20.1439));
        coordinatesMap.put("Irig", new Coordinates(45.0986, 19.8564));
        coordinatesMap.put("Lučani", new Coordinates(43.8650, 20.1361));
        coordinatesMap.put("Alibunar", new Coordinates(45.0858, 20.9644));
        coordinatesMap.put("Sivac", new Coordinates(45.6986, 19.3786));
        coordinatesMap.put("Kosjerić", new Coordinates(43.9950, 19.9119));
        coordinatesMap.put("Preševo", new Coordinates(42.3078, 21.6472));
        coordinatesMap.put("Palić", new Coordinates(46.1031, 19.7581));
        coordinatesMap.put("Nova Varoš", new Coordinates(43.4617, 19.8117));
        coordinatesMap.put("Blace", new Coordinates(43.2928, 21.2847));
        coordinatesMap.put("Topola", new Coordinates(44.2539, 20.6844));
        coordinatesMap.put("Petrovac na Mlavi", new Coordinates(44.3794, 21.4178));
        coordinatesMap.put("Batočina", new Coordinates(44.1508, 21.0744));
        coordinatesMap.put("Mali Zvornik", new Coordinates(44.3931, 19.1128));
        coordinatesMap.put("Vladimirci", new Coordinates(44.6189, 19.7844));
        coordinatesMap.put("Žitorađa", new Coordinates(43.1917, 21.7067));
        coordinatesMap.put("Despotovac", new Coordinates(44.0917, 21.4375));
        coordinatesMap.put("Varvarin", new Coordinates(43.7200, 21.3581));
        coordinatesMap.put("Opovo", new Coordinates(45.0536, 20.4281));
        coordinatesMap.put("Mionica", new Coordinates(44.2536, 20.0864));
        coordinatesMap.put("Koceljeva", new Coordinates(44.4683, 19.8200));
        coordinatesMap.put("Čajetina", new Coordinates(43.7514, 19.7133));
        coordinatesMap.put("Bajmok", new Coordinates(45.9606, 19.4267));
        coordinatesMap.put("Bogatić", new Coordinates(44.8400, 19.4806));
        coordinatesMap.put("Lapovo", new Coordinates(44.1833, 21.0958));
        coordinatesMap.put("Rača", new Coordinates(44.2311, 20.9789));
        coordinatesMap.put("Nova Crnja", new Coordinates(45.6972, 20.5986));
        coordinatesMap.put("Medveđa", new Coordinates(42.8306, 21.5781));
        coordinatesMap.put("Veliko Gradište", new Coordinates(44.7653, 21.5208));
        coordinatesMap.put("Ćićevac", new Coordinates(43.7189, 21.4553));
        coordinatesMap.put("Golubac", new Coordinates(44.6553, 21.6306));
        coordinatesMap.put("Pećinci", new Coordinates(44.9100, 19.9650));
        coordinatesMap.put("Lozovik", new Coordinates(44.4917, 21.0833));
        coordinatesMap.put("Banatski Karlovac", new Coordinates(45.0472, 21.0217));
        coordinatesMap.put("Mali Iđoš", new Coordinates(45.6900, 19.6761));
        coordinatesMap.put("Bosilegrad", new Coordinates(42.4981, 22.4692));
        coordinatesMap.put("Ljig", new Coordinates(44.2253, 20.2353));
        coordinatesMap.put("Arilje", new Coordinates(43.7539, 20.0958));
        coordinatesMap.put("Grocka", new Coordinates(44.6697, 20.7172));
        coordinatesMap.put("Žagubica", new Coordinates(44.1933, 21.7883));
        coordinatesMap.put("Bavanište", new Coordinates(44.8236, 20.8681));
        coordinatesMap.put("Vranjska Banja", new Coordinates(42.5544, 21.9664));
        coordinatesMap.put("Rekovac", new Coordinates(43.8706, 21.0911));
        coordinatesMap.put("Mol", new Coordinates(45.7619, 20.1314));
        coordinatesMap.put("Sopot", new Coordinates(44.5231, 20.5772));
        coordinatesMap.put("Malo Crniće", new Coordinates(44.5619, 21.3050));
        coordinatesMap.put("Starčevo", new Coordinates(44.8106, 20.6964));
        coordinatesMap.put("Kačarevo", new Coordinates(44.9769, 20.7275));
        coordinatesMap.put("Kučevo", new Coordinates(44.4797, 21.6711));
        coordinatesMap.put("Surdulica", new Coordinates(42.6903, 22.1700));
        coordinatesMap.put("Banatsko Novo Selo", new Coordinates(45.0147, 20.7811));
        coordinatesMap.put("Žabari", new Coordinates(44.3586, 21.2150));
        coordinatesMap.put("Bački Petrovac", new Coordinates(45.3606, 19.5928));
        coordinatesMap.put("Gajdobra", new Coordinates(45.3583, 19.2833));
        coordinatesMap.put("Padina", new Coordinates(45.1206, 20.7269));
        coordinatesMap.put("Klek", new Coordinates(45.3986, 20.4764));
        coordinatesMap.put("Ečka", new Coordinates(45.3206, 20.4400));
        coordinatesMap.put("Melenci", new Coordinates(45.5178, 20.3194));
        coordinatesMap.put("Elemir", new Coordinates(45.4206, 20.3144));
        coordinatesMap.put("Aradac", new Coordinates(45.3833, 20.3000));
        coordinatesMap.put("Bačko Gradište", new Coordinates(45.5514, 20.0339));
        coordinatesMap.put("Ruski Krstur", new Coordinates(45.5619, 19.4161));
        coordinatesMap.put("Savino Selo", new Coordinates(45.5067, 19.5417));
        coordinatesMap.put("Kucura", new Coordinates(45.5217, 19.5967));
        coordinatesMap.put("Ravno Selo", new Coordinates(45.4744, 19.6083));
        coordinatesMap.put("Zmajevo", new Coordinates(45.4272, 19.6883));
        coordinatesMap.put("Stepanovićevo", new Coordinates(45.4181, 19.7564));
        coordinatesMap.put("Kisač", new Coordinates(45.3583, 19.7667));
        coordinatesMap.put("Rumenka", new Coordinates(45.2889, 19.7419));
        coordinatesMap.put("Kać", new Coordinates(45.3083, 19.9300));
        coordinatesMap.put("Budisava", new Coordinates(45.3075, 19.9889));
        coordinatesMap.put("Kovilj", new Coordinates(45.2319, 20.0217));
        coordinatesMap.put("Mošorin", new Coordinates(45.3039, 20.1833));
        coordinatesMap.put("Đurđevo", new Coordinates(45.3389, 20.0833));
        coordinatesMap.put("Gospođinci", new Coordinates(45.4272, 19.9753));
        coordinatesMap.put("Nadalj", new Coordinates(45.5864, 19.9258));
        coordinatesMap.put("Čurug", new Coordinates(45.4711, 20.0681));
        coordinatesMap.put("Bački Jarak", new Coordinates(45.3639, 19.8833));
        coordinatesMap.put("Sirig", new Coordinates(45.4439, 19.8083));
        coordinatesMap.put("Feketić", new Coordinates(45.6056, 19.6083));
        coordinatesMap.put("Lovćenac", new Coordinates(45.6583, 19.6250));
        coordinatesMap.put("Pačir", new Coordinates(45.9303, 19.5256));
        coordinatesMap.put("Stanišić", new Coordinates(45.9075, 19.2864));
        coordinatesMap.put("Čonoplja", new Coordinates(45.8556, 19.3083));
        coordinatesMap.put("Kljajićevo", new Coordinates(45.8275, 19.4161));
        coordinatesMap.put("Bezdan", new Coordinates(45.8453, 18.9278));
        coordinatesMap.put("Bački Monoštor", new Coordinates(45.7989, 18.9358));
        coordinatesMap.put("Ratkovo", new Coordinates(45.4683, 19.3364));
        coordinatesMap.put("Kruščić", new Coordinates(45.6414, 19.4114));
        coordinatesMap.put("Mokrin", new Coordinates(45.9361, 20.4150));
        coordinatesMap.put("Horgoš", new Coordinates(46.1575, 19.9711));

        return coordinatesMap;
    }

    private static class Coordinates {
        private final double x;
        private final double y;

        public Coordinates(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }
    }
}