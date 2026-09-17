package com.securityexpert.nexus.ui2.service.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.persistence.identity.RbacRoleRecord;
import com.securityexpert.nexus.ui2.persistence.identity.RbacRoleRepository;

@RestController
@RequestMapping("/roles")
public class RbacRoleController {

    private final RbacRoleRepository roleRepository;

    public RbacRoleController(RbacRoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    @GetMapping
    public ResponseEntity<List<RbacRoleRecord>> listRoles() {
        return ResponseEntity.ok(roleRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<?> createRole(@RequestBody Map<String, Object> payload) {
        List<String> permissions = payload.get("permissions") instanceof List<?> list
                ? list.stream().map(Object::toString).toList() : List.of();
        RbacRoleRecord record = new RbacRoleRecord(
                UUID.randomUUID(),
                String.valueOf(payload.get("name")),
                String.valueOf(payload.get("token_string")),
                payload.get("description") != null ? String.valueOf(payload.get("description")) : "",
                false,
                permissions
        );
        roleRepository.insert(record);
        return ResponseEntity.ok(Map.of("id", record.id().toString()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateRole(@PathVariable UUID id, @RequestBody Map<String, Object> payload) {
        var existing = roleRepository.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (existing.get().isSystem()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot modify system roles"));
        }
        List<String> permissions = payload.get("permissions") instanceof List<?> list
                ? list.stream().map(Object::toString).toList() : existing.get().permissions();
        RbacRoleRecord updated = new RbacRoleRecord(
                id,
                String.valueOf(payload.get("name")),
                String.valueOf(payload.get("token_string")),
                payload.get("description") != null ? String.valueOf(payload.get("description")) : "",
                false,
                permissions
        );
        roleRepository.update(updated);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteRole(@PathVariable UUID id) {
        var existing = roleRepository.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (existing.get().isSystem()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot delete system roles"));
        }
        roleRepository.delete(id);
        return ResponseEntity.ok().build();
    }
}
