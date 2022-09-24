# Death Tracker

Discord bot to track when players are killed by nemesis bosses or rare bestiary

# Deployment steps

Build docker image  
1. `cd death-tracker`
1. `sbt docker:publishLocal`

Copy to server  
1. `docker images`
1. `docker save <image_id> | bzip2 | ssh bots docker load`

On the server
1. Create an env file with the missing data from `src/main/resources/application.conf`
1. Run the docker container, pointing to the env file created in step 1: `docker run --rm -d --env-file prod.env --name death-tracker <image_id>`
