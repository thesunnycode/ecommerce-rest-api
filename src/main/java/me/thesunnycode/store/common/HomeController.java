package me.thesunnycode.store.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {
    @Value("${spring.application.name}")
    private String applicationName;

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("applicationName", applicationName);

        return "index";
    }
}
