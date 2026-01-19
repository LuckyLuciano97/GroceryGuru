package org.example.groceryguru.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.groceryguru.dto.ProductRequestDto;
import org.example.groceryguru.model.Product;
import org.example.groceryguru.repository.ProductRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepo productRepo;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        productRepo.deleteAll();
    }

    @Test
    void shouldCreateProduct() throws Exception {
        ProductRequestDto request = new ProductRequestDto("Test Product", "Test Description");

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Test Product"))
                .andExpect(jsonPath("$.description").value("Test Description"))
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    void shouldGetAllProducts() throws Exception {
        Product product = new Product();
        product.setName("Product 1");
        product.setDescription("Description 1");
        productRepo.save(product);

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Product 1"));
    }

    @Test
    void shouldGetProductById() throws Exception {
        Product product = new Product();
        product.setName("Product 1");
        productRepo.save(product);

        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(product.getId()))
                .andExpect(jsonPath("$.name").value("Product 1"));
    }

    @Test
    void shouldReturn404WhenProductNotFound() throws Exception {
        mockMvc.perform(get("/api/products/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Product not found: 999"));
    }

    @Test
    void shouldSearchProductsByName() throws Exception {
        Product product = new Product();
        product.setName("Milk 1L");
        productRepo.save(product);

        mockMvc.perform(get("/api/products/search")
                        .param("name", "milk"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Milk 1L"));
    }

    @Test
    void shouldUpdateProduct() throws Exception {
        Product product = new Product();
        product.setName("Old Name");
        productRepo.save(product);

        ProductRequestDto request = new ProductRequestDto("New Name", "New Description");

        mockMvc.perform(put("/api/products/" + product.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name"))
                .andExpect(jsonPath("$.description").value("New Description"));
    }

    @Test
    void shouldDeleteProduct() throws Exception {
        Product product = new Product();
        product.setName("Product to delete");
        productRepo.save(product);

        mockMvc.perform(delete("/api/products/" + product.getId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturn400WhenProductNameIsBlank() throws Exception {
        ProductRequestDto request = new ProductRequestDto("", "Description");

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
    }
}
