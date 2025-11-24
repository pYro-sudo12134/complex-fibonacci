# Fibonacci Calculator

RESTful microservice for calculating Fibonacci numbers in the complex domain using Binet's formula.

## Features

- **Complex Number Support**: Calculate Fibonacci values for complex arguments
- **High Performance**: Async I/O with Vert.x and Redis caching
- **Resilience**: Circuit breaker and rate limiting patterns
- **Provisioning/Metrics**: Prometheus, Grafana
- **Dockerized**: Complete containerized deployment(Docker + Docker Compose)

## API Usage

### GET Request
```bash
curl -X GET "http://localhost:8080/fibonacci?number=3.5+2.1"
```

### POST Request
```bash
curl -X POST http://localhost:8080/fibonacci \
  -H "Content-Type: application/json" \
  -d '{"number": "3.5 2.1"}'
```

**Number formats accepted:**
- `"5"` (real number) 
- `"3.5+2.1"` (comma separator, but note that the imaginary unit is omitted in 

## Mathematical Approach

This implementation uses the generalized Binet's formula for complex numbers:

```
F(z) = (φ^z - ψ^z)/(2φ-1)

where:

φ = (1 + √5)/2 ≈ 1.618 (golden ratio),
ψ = (1 - √5)/2 ≈ -0.618
```

For complex exponentiation, I use logarithmic form:
```
φ^z = exp(z × ln(φ))
```

### Why This Approach?
- **Iterative methods**: Only work for integers, O(n) complexity
- **Matrix exponentiation**: Difficult to generalize for complex exponents
- **Binet's formula**: Natural extension to complex plane with O(1) computation time (but the computations themselves are algorithmically not that fancy, but this is the tradeoff). But choosing the exponentiation with the logarithms holds due to the simplicity of calculating the exponent, without precision loss in comparison to the basic formula.

## Deployment

```bash
docker-compose -f <path_to_compose.yaml> up -d
```

Services:
- **Application**: localhost:8080
- **Redis** localhost:6379
- **Prometheus** localhost:9090
- **Grafana** localhost:3000

## Technology Stack

- Java 17
- Vert.x 4.4 (reactive toolkit)
- Redis 7 (caching)
- Resilience4j (rate limiting)
- Apache Commons Math (complex arithmetic)

## Configuration

Environment variables:
- `REDIS_URL`: Redis connection string (default: `redis://redis:6379`)
- `APP_PORT`: HTTP server port (default: 8080)
- `JAVA_OPTS`: JVM options

so this might be used as a sample
