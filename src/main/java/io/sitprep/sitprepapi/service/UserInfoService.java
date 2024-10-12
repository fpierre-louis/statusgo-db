package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime; // <-- Add this import statement
import java.util.List;
import java.util.Optional;

@Service
public class UserInfoService {

    @Autowired
    private UserInfoRepo userInfoRepo;

    public UserInfo createUser(UserInfo userInfo) {
        return userInfoRepo.save(userInfo);
    }

    public Optional<UserInfo> getUserById(String id) {
        return userInfoRepo.findById(id);
    }

    public Optional<UserInfo> getUserByEmail(String email) {
        return userInfoRepo.findByUserEmail(email);
    }

    public List<UserInfo> getAllUsers() {
        return userInfoRepo.findAll();
    }

    public void deleteUser(String id) {
        userInfoRepo.deleteById(id);
    }

    // Updated logic to increment activeGroupAlertCounts
    public UserInfo updateUser(UserInfo userDetails) {
        Optional<UserInfo> optionalUser = userInfoRepo.findById(userDetails.getId());
        if (optionalUser.isPresent()) {
            UserInfo existingUser = optionalUser.get();

            // Update the fields from the request
            existingUser.setUserEmail(userDetails.getUserEmail());
            existingUser.setUserFirstName(userDetails.getUserFirstName());
            existingUser.setUserLastName(userDetails.getUserLastName());
            existingUser.setUserStatus(userDetails.getUserStatus());
            existingUser.setTitle(userDetails.getTitle());
            existingUser.setSubscription(userDetails.getSubscription());
            existingUser.setSubscriptionPackage(userDetails.getSubscriptionPackage());
            existingUser.setDateSubscribed(userDetails.getDateSubscribed());
            existingUser.setFcmtoken(userDetails.getFcmtoken());
            existingUser.setManagedGroupIDs(userDetails.getManagedGroupIDs());
            existingUser.setJoinedGroupIDs(userDetails.getJoinedGroupIDs());
            existingUser.setProfileImageURL(userDetails.getProfileImageURL());
            existingUser.setStatusColor(userDetails.getStatusColor());

            // Update the group alert count increment or decrement based on the request
            existingUser.setActiveGroupAlertCounts(userDetails.getActiveGroupAlertCounts());

            // Update the timestamp
            existingUser.setGroupAlertLastUpdated(LocalDateTime.now());

            // Ensure phone and address fields are being updated
            existingUser.setPhone(userDetails.getPhone());
            existingUser.setAddress(userDetails.getAddress());

            return userInfoRepo.save(existingUser);
        } else {
            throw new RuntimeException("User not found");
        }
    }
}
