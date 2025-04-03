document.getElementById("buscarConselho").addEventListener("click", async function () {
    const resposta = await fetch("https://api.kanye.rest/");
    const dados = await resposta.json();
    const frase = dados.quote;

    document.getElementById("fraseKanye").textContent = `"${frase}"`;
    document.querySelector(".resposta-section").style.display = "block";

    
    const imagens = [
        "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRhF-9rxJBTTBKNb6COmAruR6mu5Rmk5xkokw&s",
        "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSPcMSrlxQQYoZNBswdrkFrOihrU0f0LUXZcyqe3rs41F9tYxCxc3meXEG9w6KEBdJkBWo&usqp=CAU",
        "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQx6nr-EFYZ9CXrXAN9yfC0zH77cXTXI7fE5g&s",
        "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQtXNWss3k7-sOc1f9LU0tH4R_7u5FUFX6MMA&s",
        "https://www.shutterstock.com/editorial/image-editorial/MeTdId52NfjaY93cODQ0MzY=/kanye-west-dropped-by-adidas-rapper-girlfriend-440nw-12847842o.jpg"
    ];

    
    const imagemAleatoria = imagens[Math.floor(Math.random() * imagens.length)];

   
    const imgElement = document.getElementById("imgKanye");
    imgElement.src = imagemAleatoria;
    imgElement.style.display = "block";
});
