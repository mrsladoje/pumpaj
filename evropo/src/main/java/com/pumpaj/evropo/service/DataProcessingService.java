package com.pumpaj.evropo.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pumpaj.evropo.model.Protest;
import com.pumpaj.evropo.model.Day;
import com.pumpaj.evropo.repository.ProtestRepository;
import com.pumpaj.evropo.repository.DayRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class DataProcessingService {

    private final ProtestRepository protestRepository;
    private final DayRepository dayRepository;

    @Autowired
    public DataProcessingService(ProtestRepository protestRepository, DayRepository dayRepository) {
        this.protestRepository = protestRepository;
        this.dayRepository = dayRepository;
    }

    /**
     * Process protest JSON data
     * Checks if protest exists using organizer, location, and date as unique identifiers
     * If it exists, updates with new information
     * If it doesn't exist, creates a new protest record
     */
    public void processProtestJson(ObjectNode protestJson) {
        String organizer = protestJson.path("organizer").asText();
        String location = protestJson.path("location").asText();
        String date = standardizeDate(protestJson.path("date").asText());

        // Check if all required fields are present
        if (organizer.isEmpty() || location.isEmpty() || date.isEmpty()) {
            System.out.println("Protest JSON missing required fields. Skipping.");
            return;
        }

        Optional<Protest> existingProtest = protestRepository.findByOrganizerAndLocationAndDate(organizer, location, date);

        if (existingProtest.isPresent()) {
            // Update existing protest with new information
            Protest protest = existingProtest.get();

            // Update count if it exists in the JSON and not in the database
            if (protestJson.has("count")) {
                ObjectNode countNode = (ObjectNode) protestJson.path("count");
                if (protest.getCount() == null) {
                    Protest.Count count = new Protest.Count();

                    if (countNode.has("government") && !countNode.path("government").isNull()) {
                        count.setGovernment(countNode.path("government").asInt());
                    }

                    if (countNode.has("independent") && !countNode.path("independent").isNull()) {
                        count.setIndependent(countNode.path("independent").asInt());
                    }

                    protest.setCount(count);
                } else {
                    Protest.Count count = protest.getCount();

                    if (count.getGovernment() == null && countNode.has("government") && !countNode.path("government").isNull()) {
                        count.setGovernment(countNode.path("government").asInt());
                    }

                    if (count.getIndependent() == null && countNode.has("independent") && !countNode.path("independent").isNull()) {
                        count.setIndependent(countNode.path("independent").asInt());
                    }
                }
            }

            // Update coordinates if they exist in the JSON and not in the database
            if (protest.getX() == null && protestJson.has("x") && !protestJson.path("x").isNull()) {
                protest.setX(protestJson.path("x").asDouble());
            }

            if (protest.getY() == null && protestJson.has("y") && !protestJson.path("y").isNull()) {
                protest.setY(protestJson.path("y").asDouble());
            }

            protestRepository.save(protest);
            System.out.println("Updated existing protest: " + organizer + ", " + location + ", " + date);
        } else {
            // Create new protest
            Protest protest = new Protest();
            protest.setOrganizer(organizer);
            protest.setLocation(location);
            protest.setDate(date);

            // Set count if it exists in the JSON
            if (protestJson.has("count")) {
                ObjectNode countNode = (ObjectNode) protestJson.path("count");
                Protest.Count count = new Protest.Count();

                if (countNode.has("government") && !countNode.path("government").isNull()) {
                    count.setGovernment(countNode.path("government").asInt());
                }

                if (countNode.has("independent") && !countNode.path("independent").isNull()) {
                    count.setIndependent(countNode.path("independent").asInt());
                }

                protest.setCount(count);
            }

            // Set coordinates if they exist in the JSON
            if (protestJson.has("x") && !protestJson.path("x").isNull()) {
                protest.setX(protestJson.path("x").asDouble());
            }

            if (protestJson.has("y") && !protestJson.path("y").isNull()) {
                protest.setY(protestJson.path("y").asDouble());
            }

            protestRepository.save(protest);
            System.out.println("Created new protest: " + organizer + ", " + location + ", " + date);
        }
    }

    /**
     * Process day JSON data with source awareness
     * Routes data to government or independent nested structure based on source field
     * Checks if day record exists using date as unique identifier
     * If it exists, adds the values from the JSON to the existing source-specific values
     * If it doesn't exist, creates a new day record
     */
    public void processDayJson(ObjectNode dayJson) {
        String date = standardizeDate(dayJson.path("date").asText());

        if (date.isEmpty()) {
            System.out.println("Day JSON missing required date field. Skipping.");
            return;
        }

        // Extract source - defaults to "unknown"
        String source = dayJson.path("source").asText("unknown");

        Optional<Day> existingDay = dayRepository.findByDate(date);
        Day day;

        if (existingDay.isPresent()) {
            day = existingDay.get();
        } else {
            day = new Day();
            day.setDate(date);
        }

        // Route to source-specific processing
        if ("government".equals(source)) {
            processSourceData(dayJson, day, true);
        } else if ("independent".equals(source)) {
            processSourceData(dayJson, day, false);
        } else {
            System.out.println("Warning: Unknown source '" + source + "' for day " + date + ". Skipping source-specific fields.");
        }

        // Propaganda and pro-protest counts are already source-aware in Python
        // (forced to 0 based on source), so they aggregate at the root level
        if (dayJson.has("propaganda_count") && !dayJson.path("propaganda_count").isNull()) {
            Integer currentValue = day.getPropagandaCount();
            if (currentValue == null) {
                currentValue = 0;
            }
            day.setPropagandaCount(currentValue + dayJson.path("propaganda_count").asInt());
        }

        if (dayJson.has("pro_protest_count") && !dayJson.path("pro_protest_count").isNull()) {
            Integer currentValue = day.getProProtestCount();
            if (currentValue == null) {
                currentValue = 0;
            }
            day.setProProtestCount(currentValue + dayJson.path("pro_protest_count").asInt());
        }

        dayRepository.save(day);
    }

    /**
     * Process source-specific data fields
     * Routes data to either government or independent nested structure
     */
    private void processSourceData(ObjectNode dayJson, Day day, boolean isGovernment) {
        Day.SourceData sourceData;

        if (isGovernment) {
            if (day.getGovernment() == null) {
                day.setGovernment(new Day.SourceData());
            }
            sourceData = day.getGovernment();
        } else {
            if (day.getIndependent() == null) {
                day.setIndependent(new Day.SourceData());
            }
            sourceData = day.getIndependent();
        }

        // Update state driven messaging
        if (dayJson.has("state_driven_messaging") && !dayJson.path("state_driven_messaging").isNull()) {
            Integer currentValue = sourceData.getStateDrivenMessaging();
            if (currentValue == null) {
                currentValue = 0;
            }
            sourceData.setStateDrivenMessaging(currentValue + dayJson.path("state_driven_messaging").asInt());
        }

        // Update pro student messaging
        if (dayJson.has("pro_student_messaging") && !dayJson.path("pro_student_messaging").isNull()) {
            Integer currentValue = sourceData.getProStudentMessaging();
            if (currentValue == null) {
                currentValue = 0;
            }
            sourceData.setProStudentMessaging(currentValue + dayJson.path("pro_student_messaging").asInt());
        }

        // Update student mentions
        if (dayJson.has("student_mentions")) {
            ObjectNode mentionsNode = (ObjectNode) dayJson.path("student_mentions");

            if (sourceData.getStudentMentions() == null) {
                sourceData.setStudentMentions(new Day.StudentMentions());
            }

            Day.StudentMentions mentions = sourceData.getStudentMentions();

            if (mentionsNode.has("good_count") && !mentionsNode.path("good_count").isNull()) {
                Integer currentValue = mentions.getGoodCount();
                if (currentValue == null) {
                    currentValue = 0;
                }
                mentions.setGoodCount(currentValue + mentionsNode.path("good_count").asInt());
            }

            if (mentionsNode.has("bad_count") && !mentionsNode.path("bad_count").isNull()) {
                Integer currentValue = mentions.getBadCount();
                if (currentValue == null) {
                    currentValue = 0;
                }
                mentions.setBadCount(currentValue + mentionsNode.path("bad_count").asInt());
            }
        }

        // Update state mentions
        if (dayJson.has("state_mentions")) {
            ObjectNode mentionsNode = (ObjectNode) dayJson.path("state_mentions");

            if (sourceData.getStateMentions() == null) {
                sourceData.setStateMentions(new Day.StateMentions());
            }

            Day.StateMentions mentions = sourceData.getStateMentions();

            if (mentionsNode.has("good_count") && !mentionsNode.path("good_count").isNull()) {
                Integer currentValue = mentions.getGoodCount();
                if (currentValue == null) {
                    currentValue = 0;
                }
                mentions.setGoodCount(currentValue + mentionsNode.path("good_count").asInt());
            }

            if (mentionsNode.has("bad_count") && !mentionsNode.path("bad_count").isNull()) {
                Integer currentValue = mentions.getBadCount();
                if (currentValue == null) {
                    currentValue = 0;
                }
                mentions.setBadCount(currentValue + mentionsNode.path("bad_count").asInt());
            }
        }
    }

    /**
     * Standardizes date format to YYYY-MM-DD HH:MM:SS
     * Handles input formats like DD.MM.YYYY or D.M.YYYY (with or without trailing dot)
     */
    private String standardizeDate(String dateString) {
        // If the date is already in YYYY-MM-DD format, return it as is
        if (dateString.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return dateString + " 00:00:00";
        }

        // Check if it's in DD.MM.YYYY format
        // The pattern allows for optional trailing dot and single-digit day/month
        if (dateString.matches("\\d{1,2}\\.\\d{1,2}\\.\\d{4}\\.?")) {
            // Remove trailing dot if present
            if (dateString.endsWith(".")) {
                dateString = dateString.substring(0, dateString.length() - 1);
            }

            // Split by dots
            String[] parts = dateString.split("\\.");
            if (parts.length == 3) {
                // Ensure day and month have two digits
                String day = parts[0].length() == 1 ? "0" + parts[0] : parts[0];
                String month = parts[1].length() == 1 ? "0" + parts[1] : parts[1];
                String year = parts[2];

                // Return in YYYY-MM-DD format
                return year + "-" + month + "-" + day + " 00:00:00";
            }
        }

        // If format is unknown, return original string
        System.out.println("Warning: Could not standardize date format for: " + dateString);
        return dateString;
    }
}