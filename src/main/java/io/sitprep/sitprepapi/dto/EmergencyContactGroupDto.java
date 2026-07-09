package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.EmergencyContact;
import io.sitprep.sitprepapi.domain.EmergencyContactGroup;

import java.util.List;

/**
 * Read shape for {@link EmergencyContactGroup} (Thin-Client Refactor Phase 3 —
 * DTO hardening). Strips {@code ownerEmail} + {@code householdId} on the group
 * and the JPA {@code group} back-reference on each contact (the entity guarded
 * that recursion with {@code @JsonManagedReference}/{@code @JsonBackReference};
 * the DTO simply omits it). Keeps ids as FE row handles.
 */
public record EmergencyContactGroupDto(
        Long id,
        String name,
        List<ContactDto> contacts
) {
    public static EmergencyContactGroupDto from(EmergencyContactGroup g) {
        List<ContactDto> contacts = g.getContacts() == null ? List.of()
                : g.getContacts().stream().map(ContactDto::from).toList();
        return new EmergencyContactGroupDto(g.getId(), g.getName(), contacts);
    }

	    public record ContactDto(
	            Long id,
	            String name,
	            String phone,
	            String email,
	            String address,
	            String role,
	            String contactType,
	            String medicalInfo,
	            String radioChannel,
	            String subjectType,
	            String subjectId,
            String subjectName
    ) {
        public static ContactDto from(EmergencyContact c) {
            return new ContactDto(
                    c.getId(),
                    c.getName(),
                    c.getPhone(),
	                    c.getEmail(),
	                    c.getAddress(),
	                    c.getRole(),
	                    c.getContactType() == null ? "OTHER" : c.getContactType().name(),
	                    c.getMedicalInfo(),
	                    c.getRadioChannel(),
	                    c.getSubjectType(),
                    c.getSubjectId(),
                    c.getSubjectName());
        }
    }
}
