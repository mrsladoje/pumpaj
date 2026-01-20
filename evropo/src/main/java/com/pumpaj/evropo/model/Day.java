package com.pumpaj.evropo.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;

@Document(collection = "days")
public class Day {
    @Id
    private String id;

    @Indexed(unique = true)
    private String date;

    // Source-aware nested structures
    private SourceData government;
    private SourceData independent;

    // These stay at root level (already source-aware via Python logic)
    private Integer propagandaCount;
    private Integer proProtestCount;

    // Inner class for source-specific data
    public static class SourceData {
        private Integer stateDrivenMessaging;
        private Integer proStudentMessaging;
        private StudentMentions studentMentions;
        private StateMentions stateMentions;

        public Integer getStateDrivenMessaging() {
            return stateDrivenMessaging;
        }

        public void setStateDrivenMessaging(Integer stateDrivenMessaging) {
            this.stateDrivenMessaging = stateDrivenMessaging;
        }

        public Integer getProStudentMessaging() {
            return proStudentMessaging;
        }

        public void setProStudentMessaging(Integer proStudentMessaging) {
            this.proStudentMessaging = proStudentMessaging;
        }

        public StudentMentions getStudentMentions() {
            return studentMentions;
        }

        public void setStudentMentions(StudentMentions studentMentions) {
            this.studentMentions = studentMentions;
        }

        public StateMentions getStateMentions() {
            return stateMentions;
        }

        public void setStateMentions(StateMentions stateMentions) {
            this.stateMentions = stateMentions;
        }
    }

    public static class StudentMentions {
        private Integer goodCount;
        private Integer badCount;

        public Integer getGoodCount() {
            return goodCount;
        }

        public void setGoodCount(Integer goodCount) {
            this.goodCount = goodCount;
        }

        public Integer getBadCount() {
            return badCount;
        }

        public void setBadCount(Integer badCount) {
            this.badCount = badCount;
        }
    }

    public static class StateMentions {
        private Integer goodCount;
        private Integer badCount;

        public Integer getGoodCount() {
            return goodCount;
        }

        public void setGoodCount(Integer goodCount) {
            this.goodCount = goodCount;
        }

        public Integer getBadCount() {
            return badCount;
        }

        public void setBadCount(Integer badCount) {
            this.badCount = badCount;
        }
    }

    // Getters and setters for main class
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public SourceData getGovernment() {
        return government;
    }

    public void setGovernment(SourceData government) {
        this.government = government;
    }

    public SourceData getIndependent() {
        return independent;
    }

    public void setIndependent(SourceData independent) {
        this.independent = independent;
    }

    public Integer getPropagandaCount() {
        return propagandaCount;
    }

    public void setPropagandaCount(Integer propagandaCount) {
        this.propagandaCount = propagandaCount;
    }

    public Integer getProProtestCount() {
        return proProtestCount;
    }

    public void setProProtestCount(Integer proProtestCount) {
        this.proProtestCount = proProtestCount;
    }
}
