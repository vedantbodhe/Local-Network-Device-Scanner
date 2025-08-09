package com.example.scanner.servlet;

import com.vaadin.flow.server.VaadinServlet;
import jakarta.servlet.annotation.WebServlet;

@WebServlet(urlPatterns = "/*", name = "VaadinServlet", asyncSupported = true)
public class AppServlet extends VaadinServlet { }
