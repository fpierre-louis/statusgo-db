package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;

@Service
public class UserInfoService {

    @Autowired
    private UserInfoRepo userInfoRepo;

    public UserInfo saveUser(UserInfo userInfo) {
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

    public UserInfo updateUserStatus(String email, String status, String color) {
        Optional<UserInfo> optionalUser = userInfoRepo.findByUserEmail(email);
        if (optionalUser.isPresent()) {
            UserInfo user = optionalUser.get();
            user.setUserStatus(status);
            user.setStatusColor(color);
            return userInfoRepo.save(user);
        } else {
            throw new RuntimeException("User not found");
        }
    }

    public UserInfo updateUserFcmToken(String id, String fcmToken) {
        Optional<UserInfo> optionalUser = userInfoRepo.findById(id);
        if (optionalUser.isPresent()) {
            UserInfo user = optionalUser.get();
            user.setFCMTokens(fcmToken);
            return userInfoRepo.save(user);
        } else {
            throw new RuntimeException("User not found");
        }
    }
}
