package com.microcreditos.microcreditosbackend.controller;

import com.microcreditos.microcreditosbackend.dto.DashboardResponse;
import com.microcreditos.microcreditosbackend.service.DashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    @Autowired
    private DashboardService dashboardService;

    @GetMapping
    public DashboardResponse obtenerResumen() {
        return dashboardService.obtenerResumen();
    }
}