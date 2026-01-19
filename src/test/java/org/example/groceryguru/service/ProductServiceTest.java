package org.example.groceryguru.service;

import jakarta.persistence.EntityNotFoundException;
import org.example.groceryguru.model.Product;
import org.example.groceryguru.repository.ProductRepo;
import org.hibernate.service.spi.InjectService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepo productRepo;

    @InjectMocks
    private ProductService productService;

    @Test
    void shouldReturnProductWhenFound() {
        Product product = new Product();
        product.setId(1L);
        product.setName("Test Product");

        when(productRepo.findById(1L)).thenReturn(Optional.of(product));

        Product result = productService.getProductById(1L);

        assertEquals("Test Product", result.getName());
        verify(productRepo, times(1)).findById(1L);


    }


    @Test
    void shouldThrowExceptionWhenProductNotFound() {
        when(productRepo.findById(999L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> {
            productService.getProductById(999L);
        });
    }
}
