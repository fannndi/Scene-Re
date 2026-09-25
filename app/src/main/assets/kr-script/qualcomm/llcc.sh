path=/sys/class/devfreq/soc:qcom,cpu-cpu-llcc-bw

lock_value () {
  chmod 644 $2
  echo $1 > $2
  chmod 444 $2
}

get_min_freq(){
  cat $path/min_freq
}
get_max_freq(){
  cat $path/max_freq
}
set_min_freq(){
  lock_value $state $path/min_freq
}
set_max_freq(){
  lock_value $state $path/max_freq
}

options(){
  for item in $(cat $path/available_frequencies)
  do
    echo $item
  done
}

visible() {
  if [[ -d $path ]]; then
    echo 1
  else
    echo 0
  fi
}

$1
