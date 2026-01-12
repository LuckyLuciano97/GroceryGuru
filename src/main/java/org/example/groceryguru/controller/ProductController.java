package org.example.groceryguru.controller;

import jakarta.validation.Valid;
import org.example.groceryguru.dto.ProductRequestDto;
import org.example.groceryguru.dto.ProductResponseDto;
import org.example.groceryguru.model.Price;
import org.example.groceryguru.model.Product;
import org.example.groceryguru.service.ProductService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;


@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public List<ProductResponseDto> getAll() {
        return productService.findAllProducts()
                .stream()
                .map(this::mapToDto)
                .toList();
    }

    @GetMapping("/{id}")
    public ProductResponseDto getById(@PathVariable Long id) {
        return mapToDto(productService.getProductById(id));
    }

    @GetMapping("/search")
    public List<ProductResponseDto> searchByName(@RequestParam String name) {
        return productService.searchProductsByName(name)
                .stream()
                .map(this::mapToDto)
                .toList();
    }

    @PostMapping
    public ResponseEntity<ProductResponseDto> create(@Valid @RequestBody ProductRequestDto request) {
        Product product = new Product();
        product.setName(request.name());
        product.setDescription(request.description());

        Product saved = productService.createProduct(product);
        return new ResponseEntity<>(mapToDto(saved), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ProductResponseDto update(@PathVariable Long id, @Valid @RequestBody ProductRequestDto request) {
        Product updated = new Product();
        updated.setName(request.name());
        updated.setDescription(request.description());

        return mapToDto(productService.updateProduct(id, updated));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        productService.delete(id);
    }

    private ProductResponseDto mapToDto(Product product) {

        return new ProductResponseDto(
                product.getId(),
                product.getName(),
                product.getDescription(),
                null
        );
    }
}