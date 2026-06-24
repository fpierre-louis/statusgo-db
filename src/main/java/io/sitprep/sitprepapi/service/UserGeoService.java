package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.util.GeoUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class UserGeoService {

    private final UserInfoRepo userInfoRepo;

    public UserGeoService(UserInfoRepo userInfoRepo) {
        this.userInfoRepo = userInfoRepo;
    }

    @Transactional(readOnly = true)
    public List<UserInfo> findWithinRadiusMiles(double lat, double lng, double miles, Instant since) {
        double radiusKm = GeoUtil.milesToKm(miles);
        GeoUtil.GeoBox box = GeoUtil.around(lat, lng, radiusKm);
        return userInfoRepo.findInBoundingBox(box.latMin(), box.latMax(), box.lngMin(), box.lngMax(), since)
                .stream()
                .filter(u -> u.getLastKnownLat() != null && u.getLastKnownLng() != null)
                .filter(u -> GeoUtil.haversineKm(lat, lng, u.getLastKnownLat(), u.getLastKnownLng()) <= radiusKm)
                .toList();
    }

    @Transactional(readOnly = true)
    public int countWithinRadiusMiles(double lat, double lng, double miles, Instant since) {
        return findWithinRadiusMiles(lat, lng, miles, since).size();
    }
}
