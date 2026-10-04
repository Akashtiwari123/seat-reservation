URL ?= http://localhost:8080
up:    ; docker compose up --build -d
down:  ; docker compose down -v
burst: ; ./burst.sh $(URL)
