package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.MealPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MealPlanRepo extends JpaRepository<MealPlan, Long> {
    @Query("SELECT m FROM MealPlan m JOIN m.admins a WHERE a.userEmail = :email")
    List<MealPlan> findMealPlansByAdminEmail(@Param("email") String email);

    @Query("SELECT m FROM MealPlan m WHERE m.owner.userEmail = :email")
    List<MealPlan> findMealPlansByOwnerEmail(@Param("email") String email);
}


