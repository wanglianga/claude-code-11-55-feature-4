package com.group.carbon.controller;

import com.group.carbon.common.AuthContext;
import com.group.carbon.common.Types.Role;
import com.group.carbon.dto.Requests.*;
import com.group.carbon.service.allocation.AllocationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 共用资源分配接口：表计/工程系数/工时维护，分配运行（等级法、平衡法同为正式口径）与证据回查。
 */
@RestController
@RequestMapping("/api/allocations")
public class AllocationController {
  private final AllocationService service;

  public AllocationController(AllocationService service) {
    this.service = service;
  }

  // ---------------- 表计 ----------------

  @GetMapping("/meters")
  public Object meters(@RequestParam(required = false) Long plantId,
                       @RequestParam(required = false) String energyType) {
    return service.listMeters(plantId, energyType);
  }

  @PostMapping("/meters")
  public Object createMeter(@Valid @RequestBody MeterReq req) {
    AuthContext.requireRole(Role.ANALYST, Role.ADMIN);
    return service.createMeter(AuthContext.require().username(), req);
  }

  @PostMapping("/meters/{id}/retire")
  public Object retireMeter(@PathVariable Long id, @RequestBody(required = false) ReasonReq req) {
    AuthContext.requireRole(Role.AUDITOR, Role.ADMIN);
    return service.retireMeter(id, req != null ? req.reason() : "工艺改造停用");
  }

  // ---------------- 工程系数 ----------------

  @GetMapping("/coefficients")
  public Object coefficients(@RequestParam(required = false) Long plantId,
                             @RequestParam(required = false) String energyType) {
    return service.listCoefficients(plantId, energyType);
  }

  @PostMapping("/coefficients")
  public Object createCoefficient(@Valid @RequestBody CoefficientReq req) {
    AuthContext.requireRole(Role.ANALYST, Role.ADMIN);
    return service.createCoefficient(AuthContext.require().username(), req);
  }

  @PostMapping("/coefficients/{id}/retire")
  public Object retireCoefficient(@PathVariable Long id, @RequestBody(required = false) CoefficientRetireReq req) {
    AuthContext.requireRole(Role.AUDITOR, Role.ADMIN);
    return service.retireCoefficient(id, req != null ? req.validTo() : null);
  }

  // ---------------- 工时 ----------------

  @GetMapping("/work-hours")
  public Object workHours(@RequestParam(required = false) Long plantId,
                          @RequestParam(required = false) String period) {
    return service.listWorkHours(plantId, period);
  }

  @PostMapping("/work-hours")
  public Object createWorkHour(@Valid @RequestBody WorkHourReq req) {
    AuthContext.requireRole(Role.ANALYST, Role.ADMIN);
    return service.createWorkHour(req);
  }

  // ---------------- 分配运行 ----------------

  @GetMapping("/runs")
  public Object runs(@RequestParam(required = false) String period,
                     @RequestParam(required = false) Long plantId,
                     @RequestParam(required = false) String energyType) {
    return service.listRuns(period, plantId, energyType);
  }

  @PostMapping("/runs")
  public Object createRun(@Valid @RequestBody AllocationRunReq req) {
    AuthContext.requireRole(Role.ANALYST, Role.ADMIN);
    return service.createRun(AuthContext.require().username(), req);
  }

  @GetMapping("/runs/{id}")
  public Map<String, Object> detail(@PathVariable Long id) {
    return service.detail(id);
  }

  @PostMapping("/runs/{id}/fix")
  public Object fix(@PathVariable Long id, @RequestBody(required = false) AllocationFixReq req) {
    AuthContext.requireRole(Role.AUDITOR, Role.ADMIN);
    return service.fix(id, AuthContext.require().username(), req != null ? req.changeNote() : null);
  }

  /** 守恒核对快捷视图。 */
  @GetMapping("/runs/{id}/conservation")
  public Map<String, Object> conservation(@PathVariable Long id) {
    return service.detail(id).get("conservation") instanceof Map<?, ?> m
        ? (Map<String, Object>) m : Map.of();
  }
}
