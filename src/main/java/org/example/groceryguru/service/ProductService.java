package org.example.groceryguru.service;

import jakarta.persistence.EntityNotFoundException;
import org.example.groceryguru.model.Product;
import org.example.groceryguru.repository.ProductRepo;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ProductService {

    private final ProductRepo productRepo;


    public ProductService(ProductRepo productRepo) {
        this.productRepo = productRepo;
    }

    public List<Product> findAllProducts(){
        return productRepo.findAll();
    }

    public List<Product> searchProductsByName(String name){
        return productRepo.findByNameContainingIgnoreCase(name);
    }

    public Product getProductById(Long id){
        return productRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Product not found: " + id));
    }

    public Product createProduct(Product product){
        return productRepo.save(product);
    }


    public Product updateProduct(Long id, Product updated) {
        Product existing = productRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Product not found: " + id));

        existing.setName(updated.getName());
        existing.setPrices(updated.getPrices());
        existing.setDescription(updated.getDescription());
        return productRepo.save(existing);
    }

    public void delete(Long id){
        if (!productRepo.existsById(id)) {
            throw new EntityNotFoundException("Product not found: " + id);
        }
        productRepo.deleteById(id);
    }

}
