// MongoDB Migration Script: Transform days collection to source-aware structure
// Reflects Serbian media reality: government media is ~95% anti-student, independent shows truth
//
// Run this script with: mongosh "your-connection-string" migrate_days_collection.js
// Or paste into MongoDB Compass shell

print("Starting migration of days collection to source-aware structure...");

// Get all days documents with the old flat structure
var cursor = db.days.find({
    // Find documents that have old flat fields (not yet migrated)
    $or: [
        { stateDrivenMessaging: { $exists: true } },
        { proStudentMessaging: { $exists: true } },
        { "studentMentions.goodCount": { $exists: true }, government: { $exists: false } }
    ]
});

var migratedCount = 0;
var skippedCount = 0;

cursor.forEach(function(doc) {
    // Skip if already migrated (has government or independent nested objects)
    if (doc.government || doc.independent) {
        print("Skipping already migrated document: " + doc.date);
        skippedCount++;
        return;
    }

    print("Migrating document for date: " + doc.date);

    // Extract old values (default to 0 if null/undefined)
    var oldStateDrivenMessaging = doc.stateDrivenMessaging || 0;
    var oldProStudentMessaging = doc.proStudentMessaging || 0;

    var oldStudentGood = (doc.studentMentions && doc.studentMentions.goodCount) || 0;
    var oldStudentBad = (doc.studentMentions && doc.studentMentions.badCount) || 0;

    var oldStateGood = (doc.stateMentions && doc.stateMentions.goodCount) || 0;
    var oldStateBad = (doc.stateMentions && doc.stateMentions.badCount) || 0;

    // Calculate new values based on Serbian media reality:
    // Government media: anti-student, pro-government
    // Independent media: truthful reporting (appears pro-student, anti-government)

    // stateDrivenMessaging: 90% government, 10% independent
    var govStateDrivenMessaging = Math.round(oldStateDrivenMessaging * 0.90);
    var indStateDrivenMessaging = oldStateDrivenMessaging - govStateDrivenMessaging;

    // proStudentMessaging: 10% government, 90% independent
    var govProStudentMessaging = Math.round(oldProStudentMessaging * 0.10);
    var indProStudentMessaging = oldProStudentMessaging - govProStudentMessaging;

    // studentMentions.goodCount: 5% government (barely positive), 95% independent
    var govStudentGood = Math.round(oldStudentGood * 0.05);
    var indStudentGood = oldStudentGood - govStudentGood;

    // studentMentions.badCount: 95% government (mostly negative), 5% independent
    var govStudentBad = Math.round(oldStudentBad * 0.95);
    var indStudentBad = oldStudentBad - govStudentBad;

    // stateMentions.goodCount: 95% government (self-promotion), 5% independent
    var govStateGood = Math.round(oldStateGood * 0.95);
    var indStateGood = oldStateGood - govStateGood;

    // stateMentions.badCount: 5% government (hiding truth), 95% independent (showing truth)
    var govStateBad = Math.round(oldStateBad * 0.05);
    var indStateBad = oldStateBad - govStateBad;

    // Build the update document
    var updateDoc = {
        $set: {
            government: {
                stateDrivenMessaging: govStateDrivenMessaging,
                proStudentMessaging: govProStudentMessaging,
                studentMentions: {
                    goodCount: govStudentGood,
                    badCount: govStudentBad
                },
                stateMentions: {
                    goodCount: govStateGood,
                    badCount: govStateBad
                }
            },
            independent: {
                stateDrivenMessaging: indStateDrivenMessaging,
                proStudentMessaging: indProStudentMessaging,
                studentMentions: {
                    goodCount: indStudentGood,
                    badCount: indStudentBad
                },
                stateMentions: {
                    goodCount: indStateGood,
                    badCount: indStateBad
                }
            }
        },
        $unset: {
            stateDrivenMessaging: "",
            proStudentMessaging: "",
            studentMentions: "",
            stateMentions: ""
        }
    };

    // Apply the update
    db.days.updateOne({ _id: doc._id }, updateDoc);

    print("  Government - stateDriven: " + govStateDrivenMessaging +
          ", proStudent: " + govProStudentMessaging +
          ", studentMentions: {good:" + govStudentGood + ", bad:" + govStudentBad + "}" +
          ", stateMentions: {good:" + govStateGood + ", bad:" + govStateBad + "}");
    print("  Independent - stateDriven: " + indStateDrivenMessaging +
          ", proStudent: " + indProStudentMessaging +
          ", studentMentions: {good:" + indStudentGood + ", bad:" + indStudentBad + "}" +
          ", stateMentions: {good:" + indStateGood + ", bad:" + indStateBad + "}");

    migratedCount++;
});

print("\n=== Migration Complete ===");
print("Documents migrated: " + migratedCount);
print("Documents skipped (already migrated): " + skippedCount);
print("\nVerify with: db.days.findOne()");
