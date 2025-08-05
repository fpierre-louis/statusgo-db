//package io.sitprep.sitprepapi.runner;
//
//import io.sitprep.sitprepapi.domain.Group;
//import io.sitprep.sitprepapi.repo.GroupRepo;
//import io.sitprep.sitprepapi.service.GroupService;
//import io.sitprep.sitprepapi.service.NotificationService;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.CommandLineRunner;
//import org.springframework.stereotype.Component;
//
//@Component
//public class GroupAlertTestRunner implements CommandLineRunner {
//
//    @Autowired
//    private GroupService groupService;
//
//    @Autowired
//    private NotificationService notificationService;
//
//    @Autowired
//    private GroupRepo groupRepo;
//
//    @Override
//    public void run(String... args) throws Exception {
//        // ✅ STEP 1: Get test group using eager fetch
//        String testGroupId = "298"; // Replace with real UUID of an existing group
//        Group group = groupRepo.findByGroupIdWithMembers(testGroupId)
//                .orElseThrow(() -> new RuntimeException("Group not found: " + testGroupId));
//
//        // ✅ STEP 2: Simulate user triggering the alert
//        String testInitiatorEmail = "aaaa@gmail.com"; // Must exist in your UserInfo table
//
//        // ✅ STEP 3: Trigger broadcast using your production logic
//        notificationService.notifyGroupAlertChange(group, "Active", testInitiatorEmail);
//
//        System.out.println("✅ Simulated group alert broadcast for groupId: " + testGroupId);
//    }
//}
